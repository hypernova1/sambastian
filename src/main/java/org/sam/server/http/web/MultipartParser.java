package org.sam.server.http.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MultipartParser {

    private final InputStream inputStream;
    private final String boundary;
    private final Map<String, String> parameters;
    private int i = 0;
    int binary;
    private int loopCnt = 0;
    private String name = "";
    private String value = "";
    private String filename = "";
    private String mimeType = "";
    private byte[] fileData = null;
    private boolean isFile = false;


    private final Map<String, Object> files = new HashMap<>();

    public MultipartParser(InputStream inputStream, String boundary, Map<String, String> parameters) {
        this.inputStream = inputStream;
        this.boundary = boundary;
        this.parameters = parameters;
    }

    public Map<String, Object> parseMultipartFiles() throws IOException {
        int inputStreamLength = inputStream.available();
        byte[] data = new byte[inputStreamLength];
        while ((binary = inputStream.read()) != -1) {
            data[i] = (byte) binary;
            if (!isEndOfLine(data, i)) {
                i++;
            }

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

                clearVariables();
            }
            if (inputStream.available() == 0) break;
        }

        return this.files;
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
            e.printStackTrace();
        }
        return Arrays.copyOfRange(data, 2, fileLength - boundary.getBytes(StandardCharsets.UTF_8).length);
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
     * 변수를 모두 초기화 한다.
     */
    private void clearVariables() {
        name = "";
        value = "";
        filename = "";
        mimeType = "";
        fileData = null;
        loopCnt = 0;
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

    private boolean isEndOfLine(byte[] data, int index) {
        return index != 0 && data[index - 1] == '\r' && data[index] == '\n';
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
}
