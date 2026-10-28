package com.openfinance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses a request body over the limit: at once when its declared length is too large, and while reading when
 * it has no declared length (chunked), so a client cannot make a service buffer an unbounded body. Ingestion
 * raises the limit in its own settings, because a fetch response is the one large body.
 */
public class BodyLimitFilter extends OncePerRequestFilter {

    private final long max;

    public BodyLimitFilter(long max) {
        this.max = max;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > max) {
            Problems.write(response, 413, "The request body is larger than " + max + " bytes.");
            return;
        }
        try {
            chain.doFilter(new Limited(request, max), response);
        } catch (TooLarge e) {
            if (!response.isCommitted()) {
                response.reset();
                Problems.write(response, 413, "The request body is larger than " + max + " bytes.");
            }
        }
    }

    static final class TooLarge extends IOException {
        TooLarge() {
            super("request body too large");
        }
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream in = super.getInputStream();
                stream = new ServletInputStream() {
                    long read;

                    @Override
                    public int read() throws IOException {
                        int b = in.read();
                        if (b >= 0 && ++read > max) {
                            throw new TooLarge();
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        int n = in.read(buf, off, len);
                        if (n > 0 && (read += n) > max) {
                            throw new TooLarge();
                        }
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return in.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return in.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        in.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
