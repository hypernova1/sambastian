package org.sam.server.http.web;

import org.sam.server.constant.ContentType;
import org.sam.server.constant.HttpMethod;
import org.sam.server.http.Cookie;
import org.sam.server.http.CookieStore;
import org.sam.server.http.Session;
import org.sam.server.http.SessionManager;
import org.sam.server.util.StringUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Request 인터페이스의 구현체입니다. 일반적인 HTTP 요청에 대한 정보를 저장합니다.
 *
 * @author hypernova1
 * @see Request
 */
public class HttpRequest implements Request {

    private final String protocol;
    private final String path;
    private final HttpMethod method;
    private final Map<String, String> headers;
    private final Map<String, String> parameterMap;
    private final String json;
    private final Set<Cookie> cookies;

    protected HttpRequest(RequestParser requestParser) {
        this.protocol = requestParser.protocol;
        this.path = requestParser.url;
        this.method = requestParser.httpMethod;
        this.headers = requestParser.headers;
        this.parameterMap = requestParser.parameters;
        this.json = requestParser.json;
        this.cookies = requestParser.cookies;
    }

    /**
     * Http 요청을 분석하여 Request 인스턴스를 반환한다.
     *
     * @param in HTTP 요청을 담은 InputStream
     * @return Request 인스턴스
     */
    public static Request from(InputStream in) {
        RequestParser requestParser = new RequestParser();
        requestParser.parse(in);
        return requestParser.createRequest();
    }

    @Override
    public String getProtocol() {
        return this.protocol;
    }

    @Override
    public String getUrl() {
        return this.path;
    }

    @Override
    public HttpMethod getMethod() {
        return this.method;
    }

    @Override
    public String getParameter(String key) {
        return this.parameterMap.get(key);
    }

    @Override
    public Map<String, String> getParameters() {
        return this.parameterMap;
    }

    @Override
    public Set<String> getParameterNames() {
        return this.parameterMap.keySet();
    }

    @Override
    public Set<String> getHeaderNames() {
        return headers.keySet();
    }

    @Override
    public String getHeader(String key) {
        return headers.get(key);
    }

    @Override
    public String getJson() {
        return json;
    }

    @Override
    public Set<Cookie> getCookies() {
        return this.cookies;
    }

    @Override
    public Session getSession() {
        Set<Cookie> cookies = this.getCookies();
        Iterator<Cookie> iterator = cookies.iterator();
        while (iterator.hasNext()) {
            Cookie cookie = iterator.next();
            if (!cookie.getName().equals("sessionId")) continue;

            Session session = SessionManager.getSession(cookie.getValue());
            if (session != null) {
                session.renewAccessTime();
                return session;
            }
            iterator.remove();
        }
        return new Session();
    }

    @Override
    public boolean isFaviconRequest() {
        return this.getUrl().equals("/favicon.ico");
    }

    @Override
    public boolean isResourceRequest() {
        return this.getUrl().startsWith("/resources");
    }

    @Override
    public boolean isIndexRequest() {
        return this.getUrl().equals("/") && this.getMethod().equals(HttpMethod.GET);
    }

    @Override
    public boolean isOptionsRequest() {
        return this.getMethod().equals(HttpMethod.OPTIONS);
    }

    /**
     * 소켓으로 부터 받은 InputStream을 읽어 Request 인스턴스를 생성하는 클래스입니다.
     *
     * @author hypernova1
     * @see Request
     * @see HttpRequest
     * @see HttpMultipartRequest
     */
    protected static class RequestParser {
        protected String protocol;
        protected String url;
        protected HttpMethod httpMethod;
        protected Map<String, String> headers = new HashMap<>();
        protected ContentType contentType;
        protected String boundary;
        protected Map<String, String> parameters = new HashMap<>();
        protected String json;
        protected Set<Cookie> cookies = new HashSet<>();
        protected Map<String, Object> files = new HashMap<>();

        /**
         * InputStream에서 HTTP 본문을 읽은 후 파싱합니다.
         *
         * @param in 소켓의 InputStream
         */
        private void parse(InputStream in) {
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

            if (existsHttpBody()) {
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
                throw new RuntimeException(e);
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
                throw new RuntimeException(e);
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
                if (existsParameterValue(parameterPair)) {
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
                throw new RuntimeException(e);
            }
        }

        /**
         * multipart/form-data 본문을 한 파트씩 파싱합니다.
         *
         * @param inputStream 소켓의 InputStream
         * @throws IOException InputStream을 읽다가 오류 발생시
         */
        private void parseMultipartLine(InputStream inputStream) throws IOException {
            int i = 0;
            int loopCnt = 0;
            String name = "";
            String value = "";
            String filename = "";
            String mimeType = "";
            byte[] fileData = null;
            boolean isFile = false;
            int inputStreamLength = inputStream.available();
            byte[] data = new byte[inputStreamLength];
            int binary;
            while ((binary = inputStream.read()) != -1) {
                data[i] = (byte) binary;
                if (isEndOfLine(data, i)) {
                    data = Arrays.copyOfRange(data, 0, i);
                    String line = new String(data, StandardCharsets.UTF_8);
                    data = new byte[inputStreamLength];
                    i = 0;
                    if (loopCnt == 0) {
                        loopCnt++;
                        int index = line.indexOf("\"");
                        if (index == -1) continue;
                        String[] split = line.split("\"");
                        name = split[1];
                        if (split.length == 5) {
                            filename = split[3];
                            isFile = true;
                        }
                        continue;
                    } else if (loopCnt == 1 && isFile) {
                        int index = line.indexOf(": ");
                        mimeType = line.substring(index + 2);
                        fileData = parseFile(inputStream);
                        loopCnt = 0;
                        if (fileData == null) continue;
                        line = boundary;
                    } else if (loopCnt == 1 && !line.contains(boundary)) {
                        value = line;
                        loopCnt = 0;
                        continue;
                    }

                    if (line.contains(boundary)) {
                        if (!filename.isEmpty()) {
                            createMultipartFile(name, filename, mimeType, fileData);
                        } else {
                            this.parameters.put(name, value);
                        }

                        name = "";
                        value = "";
                        filename = "";
                        mimeType = "";
                        fileData = null;
                        loopCnt = 0;
                    }
                    if (inputStream.available() == 0) return;
                }
                i++;
            }
        }

        /**
         * multipart/form-data로 받은 파일을 인스턴스로 만듭니다.
         *
         * @param name     파일 이름
         * @param filename 파일 전체 이름
         * @param mimeType 미디어 타입
         * @param fileData 파일의 데이터
         * @see MultipartFile
         */
        private void createMultipartFile(String name, String filename, String mimeType, byte[] fileData) {
            MultipartFile multipartFile = new MultipartFile(filename, mimeType, fileData);
            if (this.files.get(name) == null) {
                this.files.put(name, multipartFile);
                return;
            }
            Object file = this.files.get(name);
            addMultipartFile(name, multipartFile, file);
        }

        /**
         * MultipartFile을 추가합니다.
         *
         * @param name          MultipartFile의 이름
         * @param multipartFile MultipartFile 인스턴스
         * @param file          MultipartFile 목록 또는 MultipartFile
         */
        @SuppressWarnings("unchecked")
        private void addMultipartFile(String name, MultipartFile multipartFile, Object file) {
            if (file.getClass().equals(ArrayList.class)) {
                ((ArrayList<MultipartFile>) file).add(multipartFile);
                return;
            }
            List<MultipartFile> files = new ArrayList<>();
            MultipartFile preFile = (MultipartFile) file;
            files.add(preFile);
            files.add(multipartFile);
            this.files.put(name, files);
        }

        /**
         * Multipart boundary를 기준으로 파일을 읽어 들이고 바이트 배열을 반환합니다.
         *
         * @param inputStream 소켓의 InputStream
         * @return 파일의 바이트 배열
         */
        private byte[] parseFile(InputStream inputStream) {
            byte[] data = new byte[1024 * 8];
            int fileLength = 0;
            try {
                int i;
                while ((i = inputStream.read()) != -1) {
                    if (isFullCapacity(data, fileLength)) {
                        data = getDoubleArray(data);
                    }
                    data[fileLength] = (byte) i;
                    if (isEndOfLine(data, fileLength)) {
                        String content = new String(data, StandardCharsets.UTF_8);
                        if (isEmptyBoundaryContent(content)) return null;
                        if (isEndOfBoundaryLine(content)) break;
                    }
                    fileLength++;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return Arrays.copyOfRange(data, 2, fileLength - boundary.getBytes(StandardCharsets.UTF_8).length);
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
        private boolean existsParameterValue(String[] parameterPair) {
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
        private boolean existsHttpBody() {
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

        /**
         * boundary 라인이 끝났는지 확인합니다.
         *
         * @param content multipart 본문 라인
         * @return boundary 라인이 끝났는지 여부
         */
        private boolean isEndOfBoundaryLine(String content) {
            String boundary = new String(this.boundary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            return content.contains(boundary);
        }

        /**
         * boundary 라인이 존재하는 지 확인합니다.
         *
         * @param content multipart 본문 라인
         * @return boundary 라인 존재 여부
         */
        private boolean isEmptyBoundaryContent(String content) {
            return content.trim().equals(this.boundary);
        }

        /**
         * 입력받은 배열의 길이의 두배인 배열을 생성하고 카피 후 반환합니다.
         *
         * @param data 배열
         * @return 2배 길이의 배열
         */
        private byte[] getDoubleArray(byte[] data) {
            byte[] arr = new byte[data.length * 2];
            System.arraycopy(data, 0, arr, 0, data.length);
            return arr;
        }

        /**
         * 배열의 길이가 최대인지 확인합니다.
         *
         * @param data       확인할 배열
         * @param fileLength 파일 길이
         * @return 배열의 길이가 최대인지 여부
         */
        private boolean isFullCapacity(byte[] data, int fileLength) {
            return data.length == fileLength;
        }

    }

}
