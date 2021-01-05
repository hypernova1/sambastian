package org.sam.server.http.context;

import org.sam.server.annotation.component.Handler;
import org.sam.server.annotation.handle.Handle;
import org.sam.server.annotation.handle.PathValue;
import org.sam.server.annotation.handle.RestApi;
import org.sam.server.constant.ContentType;
import org.sam.server.constant.HttpMethod;
import org.sam.server.context.BeanContainer;
import org.sam.server.context.HandlerInfo;
import org.sam.server.exception.HandlerNotFoundException;
import org.sam.server.http.web.Request;
import org.sam.server.http.web.Response;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 실행할 핸들러를 찾는 클래스입니다.
 *
 * @author hypernova1
 */
public class HandlerFinder {

    private static final Pattern PATH_VALUE_PATTERN = Pattern.compile("[{](.*?)[}]");

    private final Request request;

    private final Response response;

    private final List<Method> pathValueHandlerMethods = new ArrayList<>();

    private final List<Method> handlerMethods = new ArrayList<>();

    private String handlerClassPath;

    private boolean isExistsPath;

    private HandlerFinder(Request request, Response response) {
        this.request = request;
        this.response = response;
    }

    /**
     * 인스턴스를 생성합니다.
     *
     * @param request 요청 인스턴스
     * @param response 응답 인스턴스
     * @return HandlerFinder 인스턴스
     * */
    public static HandlerFinder create(Request request, Response response) {
        return new HandlerFinder(request, response);
    }

    /**
     * 핸들러 클래스를 탐색하여 해당하는 핸들러의 정보를 담은 인스턴스를 생성합니다.
     *
     * @return 핸들러 정보 인스턴스
     * @throws HandlerNotFoundException 홴들러를 찾지 못 했을 시
     * @see org.sam.server.context.HandlerInfo
     * */
    public HandlerInfo createHandlerInfo() throws HandlerNotFoundException {
        List<Object> handlerInstances = BeanContainer.getHandlerBeans();
        for (Object handlerInstance : handlerInstances) {
            Class<?> handlerType = handlerInstance.getClass();
            classifyHandler(handlerType);
            this.handlerClassPath = handlerType.getDeclaredAnnotation(Handler.class).value();
            Method handlerMethod = findHandlerMethod();
            if (handlerMethod != null) {
                return new HandlerInfo(handlerInstance, handlerMethod);
            }
        }

        if (isExistsPath) {
            response.methodNotAllowed();
        }
        throw new HandlerNotFoundException();
    }

    /**
     * 핸들러 클래스 내부의 핸들러 메서드를 찾습니다.
     *
     * @return 핸들러 메서드
     * @throws HandlerNotFoundException 핸들러를 찾지 못 했을 시
     * */
    private Method findHandlerMethod() throws HandlerNotFoundException {
        String requestPath = getRequestPath();
        return Stream.of(
                findHandlerMethod(handlerMethods, requestPath),
                findHandlerMethod(pathValueHandlerMethods, requestPath)
        )
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(null);
    }

    /**
     * 어노테이션의 정보와 요청 정보가 일치하는 핸들러의 메서드를 반환합니다.
     *
     * @param handlerMethods 핸들러 메서드 목록
     * @param requestPath 요청 URL
     * @return 핸들러 메서드
     * */
    private Optional<Method> findHandlerMethod(List<Method> handlerMethods, String requestPath) {
        return handlerMethods.stream()
                .filter(handlerMethod -> isMatchMethod(handlerMethod, requestPath))
                .findFirst();
    }

    /**
     * 핸들러 메서드가 요청과 일치히는지 확인합니다.
     *
     * @param handlerMethod 한들러 메서드
     * @param requestPath 요청 URL
     * @return 일치여부
     * */
    private boolean isMatchMethod(Method handlerMethod, String requestPath) {
        Annotation[] handlerMethodDeclaredAnnotations = handlerMethod.getDeclaredAnnotations();
        return Arrays.stream(handlerMethodDeclaredAnnotations)
                .filter(this::isHandleMethod)
                .anyMatch(annotation -> compareAnnotation(requestPath, handlerMethod, annotation));
    }

    /**
     * 일반 핸들러 메서드와 url 내에 파라미터가 있는 핸들러를 분류합니다.
     *
     * @param handlerClass 핸들러 클래스
     * */
    private void classifyHandler(Class<?> handlerClass) {
        Method[] handlerClassDeclaredMethods = handlerClass.getDeclaredMethods();
        for (Method handlerMethod : handlerClassDeclaredMethods) {
            classifyHandlerMethod(handlerMethod);
        }
    }

    /**
     * 일반 핸들러 메서드와 url 내에 파라미터가 있는 핸들러를 분류합니다.
     *
     * @param handlerMethod 핸들러 메서드
     * */
    private void classifyHandlerMethod(Method handlerMethod) {
        for (Annotation annotation : handlerMethod.getDeclaredAnnotations()) {
            if (isHandleMethod(annotation)) {
                classifyHandler(handlerMethod, annotation);
            }
        }
    }

    /**
     * 일반 핸들러 메서드와 url 내에 파라미터가 있는 핸들러를 분류합니다.
     *
     * @param handlerMethod 핸들러 메서드
     * @param annotation 핸들러 메서드의 어노테이션
     * */
    private void classifyHandler(Method handlerMethod, Annotation annotation) {
        try {
            Method value = annotation.annotationType().getMethod("value");
            String path = String.valueOf(value.invoke(annotation));
            if (isUrlContainsParameter(path)) {
                pathValueHandlerMethods.add(handlerMethod);
                return;
            }
            handlerMethods.add(handlerMethod);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    /**
     * 요청 정보와 핸들러 메서드의 정보가 일치하는지 비교합니다.
     *
     * @param requestPath 요청 URL
     * @param handlerMethod 핸들러 메서드
     * @param handlerMethodDeclaredAnnotation 핸들러 메서드에 선언된 어노테이션
     * @return 일치 여부
     * */
    private boolean compareAnnotation(String requestPath, Method handlerMethod, Annotation handlerMethodDeclaredAnnotation) {
        Class<? extends Annotation> handlerAnnotationType = handlerMethodDeclaredAnnotation.annotationType();
        try {
            Method methodPropertyInAnnotation = handlerAnnotationType.getDeclaredMethod("method");
            Method pathPropertyInAnnotation = handlerAnnotationType.getDeclaredMethod("value");
            String path = pathPropertyInAnnotation.invoke(handlerMethodDeclaredAnnotation).toString();
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            if (requestPath.equals(request.getPath())) {
                path = this.handlerClassPath + path;
            }
            String method = methodPropertyInAnnotation.invoke(handlerMethodDeclaredAnnotation).toString();
            boolean isMatchMethod = compareMethodAndPath(requestPath, handlerMethod, path, method);
            if (isMatchMethod) return true;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * URL과 HTTP Method가 일치하는지 비교합니다.
     *
     * @param requestPath 요청 URL
     * @param handlerMethod 핸들러 메서드
     * @param path 핸들러 메서드의 URL
     * @param method 헨들러 메서드의 HTTP Method
     * @return 일치 여부
     * */
    private boolean compareMethodAndPath(String requestPath, Method handlerMethod, String path, String method) {
        HttpMethod httpMethod = request.getMethod();
        boolean containPathValue = isExistsPathValueAnnotation(handlerMethod);
        boolean isSamePath = requestPath.equals(path);
        if (isSamePath) {
            this.isExistsPath = true;
        }
        if (containPathValue) {
            isSamePath = findPathValueHandler(requestPath, path, isSamePath);
        }
        boolean isOptionsRequest = httpMethod.equals(HttpMethod.OPTIONS);
        if (isSamePath && isOptionsRequest) {
            response.addAllowedMethod(HttpMethod.get(method));
        }
        boolean isHeadRequest = httpMethod.equals(HttpMethod.HEAD) && HttpMethod.GET.toString().equals(method);
        if (!isOptionsRequest && isSamePath && httpMethod.equals(HttpMethod.get(method)) || isHeadRequest) {
            if (handlerMethod.getDeclaredAnnotation(RestApi.class) != null) {
                this.response.setContentMimeType(ContentType.APPLICATION_JSON);
            }
            return true;
        }
        return false;
    }

    /**
     * URL에 파라미터가 포함된 핸들러 메서드를 찾습니다.
     *
     * @param requestPath 요청 URL
     * @param path 핸들러 메서드의 URL
     * @param isSamePath 기존 일치 여부
     * @return 일치 여부
     * */
    private boolean findPathValueHandler(String requestPath, String path, boolean isSamePath) {
        Matcher matcher = PATH_VALUE_PATTERN.matcher(path);
        Queue<String> paramNames = new ArrayDeque<>();
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
        }
        if (!paramNames.isEmpty()) {
            isSamePath = matchPath(requestPath, path, paramNames);
        }
        return isSamePath;
    }

    /**
     * 핸들러 메서드에 PathValue 어노테이션이 선언되어 있는지 확인합니다.
     *
     * @param handlerMethod 핸들러 메서드
     * @return PathValue 선언 여부
     * */
    private boolean isExistsPathValueAnnotation(Method handlerMethod) {
        Parameter[] parameters = handlerMethod.getParameters();
        return Arrays.stream(parameters)
                .anyMatch(this::isDeclaredPathValueAnnotation);
    }

    /**
     * 경로에 URL이 포함 된 핸들러 메서드가 일치하는 지 확인합니다.
     *
     * @param requestPath 요청 URL
     * @param path 핸들러 메서드 URL
     * @param paramNames 핸들러 메서드 파라미터
     * @return 일치 여부
     * */
    private boolean matchPath(String requestPath, String path, Queue<String> paramNames) {
        if (!isUrlContainsParameter(path)) return false;
        String[] requestPathArr = requestPath.split("/");
        String[] pathArr = path.split("/");
        Map<String, String> param = new HashMap<>();
        if (requestPathArr.length != pathArr.length) {
            return false;
        }
        for (int i = 0; i < pathArr.length; i++) {
            if (isUrlContainsParameter(pathArr[i])) {
                param.put(paramNames.poll(), requestPathArr[i]);
                continue;
            }
            if (!pathArr[i].equals(requestPathArr[i])) {
                return false;
            }
        }
        request.getParameters().putAll(param);
        return true;
    }

    /**
     * 요청 URL과 핸들러 클래스의 URL을 비교하고 처음 부분이 일치한다면 그 부분만큼 요청 URL을 잘라내고 반환합니다.
     *
     * @return 수정된 요청 URL
     * */
    private String getRequestPath() {
        String requestPath = request.getPath();
        String rootRequestPath = "/";
        if (!requestPath.equals("/")) {
            rootRequestPath += requestPath.split("/")[1];
        }
        if (!this.handlerClassPath.startsWith("/")) {
            this.handlerClassPath = "/" + this.handlerClassPath;
        }
        if (rootRequestPath.equals(this.handlerClassPath)) {
            requestPath = replaceRequestPath(requestPath);
        }
        if (!requestPath.equals("/") && requestPath.endsWith("/")) {
            requestPath = requestPath.substring(0, requestPath.length() - 1);
        }
        return requestPath;
    }

    /**
     * 요청 URL과 핸들러 클래스의 URL을 비교하고 처음 부분이 일치한다면 그 부분만큼 요청 URL을 잘라내고 반환합니다.
     *
     * @param requestPath 요청 URL
     * @return 수정된 요청 URL
     * */
    private String replaceRequestPath(String requestPath) {
        int index = requestPath.indexOf(this.handlerClassPath);
        requestPath = requestPath.substring(index + this.handlerClassPath.length());
        if (!requestPath.startsWith("/")) requestPath = "/" + requestPath;
        return requestPath;
    }

    /**
     * 핸들러 메서드인지 확인합니다.
     *
     * @param annotation 홴들러 메서드의 어노테이션
     * @return 핸들러 메서드인지 여부
     * */
    private boolean isHandleMethod(Annotation annotation) {
        return annotation.annotationType().getDeclaredAnnotation(Handle.class) != null;
    }

    /**
     * URL 내에 파라미터가 포함되어 있는지 확인합니다.
     *
     * @param url URL
     * @return 파라미터 포함 여부
     * */
    private boolean isUrlContainsParameter(String url) {
        return url.contains("{") && url.contains("}");
    }

    /**
     * 파라미터에 PathValue 어노테이션이 선언되어 있는지 확인합니다.
     *
     * @param parameter 파라미터
     * @return PathValue 어노테이션 선언 여부
     * */
    private boolean isDeclaredPathValueAnnotation(Parameter parameter) {
        return parameter.getDeclaredAnnotation(PathValue.class) != null;
    }

}