package org.sam.server.http.web;

import org.sam.server.constant.HttpMethod;
import org.sam.server.http.Cookie;

import java.util.*;

/**
 * Multipart 요청에 대한 클래스
 *
 * @author hypernova1
 * @see HttpRequest
 */
public class HttpMultipartRequest extends HttpRequest {
    private final Map<String, Object> files;

    protected HttpMultipartRequest(RequestParser requestParser) {
        super(requestParser);
        this.files = requestParser.files;
    }

    /**
     * 해당 이름에 대한 MultipartFile을 반환합니다.
     *
     * @param name MultipartFile 이름
     * @return MultipartFile 인스턴스
     * @throws IllegalAccessException MultipartFile이 여러 개 일 시
     * */
    public MultipartFile getMultipartFile(String name) throws IllegalAccessException {
        Object obj = files.get(name);
        if (obj == null) {
            return null;
        }
        if (MultipartFile.class.equals(obj.getClass())) {
            return (MultipartFile) obj;
        }

        throw new IllegalAccessException("file size is not one.");
    }

    /**
     * 해당 이름에 대한 MultipartFile 목록을 반환합니다.
     * 
     * @param name MultipartFile 목록의 이름
     * @return MultipartFile 목록
     * */
    @SuppressWarnings("unchecked")
    public List<MultipartFile> getMultipartFileList(String name) {
        Object obj = files.get(name);
        if (obj == null) {
            return null;
        }
        if (MultipartFile.class.equals(obj.getClass())) {
            return Collections.singletonList((MultipartFile) obj);
        }

        return (ArrayList<MultipartFile>) files.get(name);
    }

}
