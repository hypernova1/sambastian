package org.sam.server.http.context;

import org.sam.server.context.HandlerInfo;
import org.sam.server.exception.HandlerNotFoundException;
import org.sam.server.http.web.HttpRequest;
import org.sam.server.http.web.HttpResponse;
import org.sam.server.http.web.Request;
import org.sam.server.http.web.Response;

import java.io.IOException;
import java.net.Socket;

/**
 * Request, Response 인스턴스를 만들고 HTTP 요청을 분기합니다.
 *
 * @author hypernova1
 * @see Request
 * @see Response
 */
public class HttpLauncher {

    /**
     * 소켓을 받아 Request, Response 인스턴스를 만든 후 핸들러 혹은 정적 자원을 찾습니다.
     *
     * @param connect 소켓
     */
    public static void execute(Socket connect) {
        try {
            Request request = HttpRequest.from(connect.getInputStream());
            if (request == null) {
                return;
            }
            Response response = HttpResponse.of(connect.getOutputStream(), request.getUrl(), request.getMethod());
            findHandler(request, response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 요청 URL을 읽어 핸들러를 찾을지 정적 자원을 찾을지 분기합니다.
     *
     * @param request  요청 인스턴스
     * @param response 응답 인스턴스
     */
    private static void findHandler(Request request, Response response) {
        if (request.isFaviconRequest()) {
            response.favicon();
            return;
        }
        if (request.isResourceRequest()) {
            response.staticResources();
            return;
        }

        if (request.isIndexRequest()) {
            response.indexFile();
            return;
        }

        if (request.isOptionsRequest()) {
            response.allowedMethods();
            return;
        }

        try {
            HandlerFinder handlerFinder = HandlerFinder.of(request, response);
            HandlerInfo handlerInfo = handlerFinder.createHandlerInfo();
            HandlerExecutor handlerExecutor = HandlerExecutor.of(request, response);
            handlerExecutor.execute(handlerInfo);
        } catch (HandlerNotFoundException e) {
            response.notFound();
        }
    }

}
