package p10.clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Installs an effective date only around the real factory HTTP request. */
public final class P10BusinessDateFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!P10BusinessClock.isAcceptanceEnabled()
                || !P10BusinessClock.appliesToFactoryUri(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        final P10BusinessClock.Context context;
        try {
            context = P10BusinessClock.readContext(request.getHeader(P10BusinessClock.PHASE_HEADER));
        } catch (RuntimeException ex) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
            return;
        }

        response.setHeader(P10BusinessClock.DATE_HEADER, context.businessDate().toString());
        response.setHeader(P10BusinessClock.ISOLATION_HEADER, "true");
        P10BusinessClock.Context previous = P10BusinessClock.enter(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            P10BusinessClock.restore(previous);
        }
    }
}
