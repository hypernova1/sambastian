package org.sam.server.http.web;

import org.sam.server.constant.ContentType;
import org.sam.server.constant.HttpMethod;
import org.sam.server.http.Cookie;
import org.sam.server.http.CookieStore;
import org.sam.server.util.StringUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 소켓으로 부터 받은 InputStream을 읽어 Request 인스턴스를 생성하는 클래스입니다.
 *
 * @author hypernova1
 * @see Request
 * @see HttpRequest
 * @see HttpMultipartRequest
 */
public class RequestParser {

    protected String protocol;

    protected String url;

    protected HttpMethod httpMethod;

    protected Map<String, String> headers = new HashMap<>();

    protected ContentType contentType;

    protected String boundary;

    protected Map<String, String> parameters = new HashMap<>();

    protected String json;

    protected Set<Cookie> cookies = new HashSet<>();

    protected final Map<String, Object> files = new HashMap<>();

    /**
     * InputStream에서 HTTP 본문을 읽은 후 파싱합니다.
     *
     * @param in 소켓의 InputStream
     */
    protected void parse(InputStream in) {
        BufferedInputStream inputStream = new BufferedInputStream(in);
        String headersPart = parseHeaderPart(inputStream);

        if (isNonHttpRequest(headersPart)) return;

        String[] headers = headersPart.split("\r\n");
        StringTokenizer tokenizer = new StringTokenizer(headers[0]);
        String httpMethodPart = tokenizer.nextToken().toUpperCase();
        String requestUrl = tokenizer.nextToken().toLowerCase();

        this.protocol = tokenizer.nextToken().toUpperCase();
        this.headers = parseHeaders(headers);
        this.httpMethod = HttpMethod.valueOf(httpMethodPart);
        this.contentType = parseContentType();

        String query = parseRequestUrl(requestUrl);

        if (StringUtils.isNotEmpty(query)) {
            this.parameters = parseQuery(query);
        }

        if (isExistsHttpBody()) {
            parseBody(inputStream);
        }
    }

    private ContentType parseContentType() {
        String contentType = this.headers.getOrDefault("content-type", "text/plain");
        ContentType result = ContentType.get(contentType);
        if (contentType.startsWith(ContentType.MULTIPART_FORM_DATA.getValue())) {
            this.boundary = "--" + contentType.split("; ")[1].split("=")[1];
            this.contentType = ContentType.get(contentType.split("; ")[0]);
        }
        return result;
    }

    /**
     * HTTP 바디에 있는 데이터를 파싱합니다.
     *
     * @param inputStream 인풋 스트림
     */
    private void parseBody(BufferedInputStream inputStream) {
        if (this.boundary != null) {
            parseMultipartBody(inputStream);
            return;
        }
        parseRequestBody(inputStream);
    }

    /**
     * HTTP 헤더를 읽어 반환합니다.
     *
     * @param inputStream 인풋 스트림
     * @return HTTP 헤더 내용
     */
    private String parseHeaderPart(BufferedInputStream inputStream) {
        int i;
        String headersPart = "";
        StringBuilder sb = new StringBuilder();
        try {
            while ((i = inputStream.read()) != -1) {
                char c = (char) i;
                sb.append(c);
                if (isEndOfHeader(sb.toString())) {
                    headersPart = sb.toString().replace("\r\n\r\n", "");
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return headersPart;
    }

    /**
     * HTTP 바디를 파싱합니다.
     *
     * @param inputStream 소켓의 InputSteam
     */
    private void parseRequestBody(InputStream inputStream) {
        StringBuilder sb = new StringBuilder();
        try {
            int binary;
            int inputStreamLength = inputStream.available();
            byte[] data = new byte[inputStreamLength];
            int i = 0;
            while ((binary = inputStream.read()) != -1) {
                data[i] = (byte) binary;
                if (isEndOfLine(data, i) || inputStream.available() == 0) {
                    data = Arrays.copyOfRange(data, 0, i + 1);
                    String line = new String(data, StandardCharsets.UTF_8);
                    sb.append(line);
                    data = new byte[inputStreamLength];
                    i = 0;
                }
                if (inputStream.available() == 0) break;
                i++;
            }
            if (isJsonRequest()) {
                this.json = sb.toString();
                return;
            }
            this.parameters = parseQuery(sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * HTTP 헤더를 파싱합니다.
     *
     * @param headers 헤더 본문
     * @return 헤더 목록
     */
    private Map<String, String> parseHeaders(String[] headers) {
        Map<String, String> result = new HashMap<>();
        for (int i = 1; i < headers.length; i++) {
            int index = headers[i].indexOf(": ");
            String key = headers[i].substring(0, index).toLowerCase();
            String value = headers[i].substring(index + 2);
            if ("cookie".equals(key)) {
                this.cookies = CookieStore.parseCookie(value);
                continue;
            }
            result.put(key, value);
        }
        return result;
    }

    /**
     * 요청 URL을 파싱하여 저장하고 쿼리 스트링을 반환합니다.
     *
     * @param url 요청 URL
     * @return 쿼리 스트링
     */
    private String parseRequestUrl(String url) {
        int index = url.indexOf("?");
        if (index == -1) {
            this.url = url;
            return "";
        }
        this.url = url.substring(0, index);
        return url.substring(index + 1);
    }

    /**
     * 쿼리 스트링을 파싱합니다.
     *
     * @param parameters 쿼리 스트링
     * @return 파라미터 목록
     */
    private Map<String, String> parseQuery(String parameters) {
        Map<String, String> map = new HashMap<>();
        String[] rawParameters = parameters.split("&");
        for (String rawParameter : rawParameters) {
            String[] parameterPair = rawParameter.split("=");
            String name = parameterPair[0];
            String value = "";
            if (isExistsParameterValue(parameterPair)) {
                value = parameterPair[1];
            }
            map.put(name, value);
        }
        return map;
    }

    /**
     * multipart/form-data 요청을 파싱합니다.
     *
     * @param inputStream 소켓의 InputStream
     */
    private void parseMultipartBody(InputStream inputStream) {
        try {
            StringBuilder sb = new StringBuilder();
            int i;
            while ((i = inputStream.read()) != -1) {
                sb.append((char) i);
                if (isBoundaryLine(sb.toString())) {
                    parseMultipartLine(inputStream);
                    return;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * multipart/form-data 본문을 한 파트씩 파싱합니다.
     *
     * @param inputStream 소켓의 InputStream
     * @throws IOException InputStream을 읽다가 오류 발생시
     */
    private void parseMultipartLine(InputStream inputStream) throws IOException {
        MultipartParser multipartParser = new MultipartParser(inputStream, boundary, parameters);
        Map<String, Object> files = multipartParser.parseMultipartFiles();
        this.files.putAll(files);
    }

    /**
     * HttpRequest 혹은 HttpMultipartRequest 인스턴스를 생성합니다.
     *
     * @return 요청 인스턴스
     * @see org.sam.server.http.web.HttpRequest
     * @see org.sam.server.http.web.HttpMultipartRequest
     */
    public Request createRequest() {
        if (headers.isEmpty()) return null;
        if (contentType == ContentType.MULTIPART_FORM_DATA) {
            return new HttpMultipartRequest(this);
        }
        return new HttpRequest(this);
    }

    /**
     * 한 줄의 마지막인지 확인합니다.
     *
     * @param data  데이터
     * @param index 인덱스
     * @return 한 줄의 마지막인지 여부
     */
    private boolean isEndOfLine(byte[] data, int index) {
        return index != 0 && data[index - 1] == '\r' && data[index] == '\n';
    }

    /**
     * 헤더의 끝 부분인지 확인합니다.
     *
     * @param data 데이터
     * @return 헤더의 끝인지 여부
     */
    private static boolean isEndOfHeader(String data) {
        String CR = "\r";
        return data.endsWith(CR + "\n\r\n");
    }

    /**
     * 파라미터에 값이 있는지 확인합니다.
     *
     * @param parameterPair 파라미터 쌍
     * @return 파라미터 값 존재 여부
     */
    private boolean isExistsParameterValue(String[] parameterPair) {
        return parameterPair.length == 2;
    }

    /**
     * HTTP 요청이 아닌지 확인합니다.
     *
     * @param headersPart 헤더
     * @return HTTP 요청이 아닌지에 대한 여부
     */
    private boolean isNonHttpRequest(String headersPart) {
        return headersPart.trim().isEmpty();
    }

    /**
     * HTTP 바디에 메시지가 존재하는 지 확인합니다.
     *
     * @return HTTP 바디에 메시지가 존재하는지 여부
     */
    private boolean isExistsHttpBody() {
        return this.httpMethod == HttpMethod.POST ||
                this.httpMethod == HttpMethod.PUT ||
                this.contentType == ContentType.APPLICATION_JSON;
    }

    /**
     * HTTP 요청 본문이 JSON인지 확인합니다.
     */
    private boolean isJsonRequest() {
        return this.contentType == ContentType.APPLICATION_JSON && this.parameters.isEmpty();
    }

    /**
     * boundary 라인인지 확인합니다.
     *
     * @param line multipart 본문 라인
     * @return boundary 라인 여부
     */
    private boolean isBoundaryLine(String line) {
        return line.contains(this.boundary + "\r\n");
    }

}