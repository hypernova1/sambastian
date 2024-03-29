package org.sam.server.context;

import org.sam.server.annotation.Qualifier;
import org.sam.server.annotation.component.Bean;
import org.sam.server.exception.BeanAccessModifierException;
import org.sam.server.exception.BeanCreationException;
import org.sam.server.exception.BeanNotFoundException;
import org.sam.server.http.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 빈을 생성하고 관리하는 클래스입니다.
 *
 * @author hypernova1
 * */
public class BeanContainer {

    private static final Logger logger = LoggerFactory.getLogger(BeanContainer.class);

    private static final Map<Class<?>, List<BeanInfo>> beanMap = new HashMap<>();

    private static final List<Object> handlerBeans = new ArrayList<>();

    private static final List<Interceptor> interceptors = new ArrayList<>();

    static {
        loadComponentBeans();
        loadHandlerBeans();
        loadInterceptors();
    }

    /**
     * 컴포넌트 클래스의 인스턴스를 생성하고 저장합니다.
     * */
    private static void loadComponentBeans() {
        for (Class<?> componentClass : BeanClassLoader.getComponentClasses()) {
            String beanName = getBeanName(componentClass);
            if (existsBean(componentClass)) continue;
            Object componentInstance = createComponentInstance(componentClass);
            Method[] declaredMethods = componentInstance.getClass().getDeclaredMethods();
            loadMethodBean(componentInstance, declaredMethods);
            addBeanMap(componentClass, componentInstance, beanName);
        }
    }

    /**
     * 컴포넌트 클래스 내부의 빈 메서드의 결과값을 받아 컴포넌트 인스턴스 목록에 추가합니다.
     *
     * @param componentInstance 컴포넌트 인스턴스
     * @param declaredMethods 컴포넌트 클래스에 선언 된 메서드 목록
     * */
    private static void loadMethodBean(Object componentInstance, Method[] declaredMethods) {
        for (Method declaredMethod : declaredMethods) {
            if (declaredMethod.getDeclaredAnnotation(Bean.class) == null) continue;
            try {
                Class<?> beanType = declaredMethod.getReturnType();
                Object instance = declaredMethod.invoke(componentInstance);
                String beanName = declaredMethod.getName();
                addBeanMap(beanType, instance, beanName);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new BeanAccessModifierException();
            }
        }
    }

    /**
     * 생성된 빈을 저장합니다.
     *
     * @param componentType 컴포넌트 타입
     * @param componentInstance 컴포넌트 인스턴스
     * @param beanName 빈 이름
     * */
    private static void addBeanMap(Class<?> componentType, Object componentInstance, String beanName) {
        BeanInfo beanInfo = new BeanInfo(beanName, componentInstance);
        logger.info("create bean: " + beanName + " > " + componentType.getName());
        if (beanMap.get(componentType) != null) {
            beanMap.get(componentType).add(beanInfo);
            return;
        }
        List<BeanInfo> beanInfoList = new ArrayList<>();
        beanInfoList.add(beanInfo);
        beanMap.put(componentType, beanInfoList);
    }

    /**
     * 핸들러 클래스의 인스턴스를 생성하고 저장합니다.
     * */
    private static void loadHandlerBeans() {
        for (Class<?> handlerClass : BeanClassLoader.getHandlerClasses()) {
            Object bean = createComponentInstance(handlerClass);
            logger.info("create handler bean: " + handlerClass.getName());
            handlerBeans.add(bean);
        }
    }

    /**
     * 인터셉터 구현체 클래스의 인스턴스를 생성하고 저장합니다.
     * */
    private static void loadInterceptors() {
        try {
            for (Class<?> interceptorClass : BeanClassLoader.getInterceptorClasses()) {
                Interceptor interceptor = (Interceptor) interceptorClass.getDeclaredConstructor().newInstance();
                interceptors.add(interceptor);
            }
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    /**
     * 컴포넌트의 인스턴스를 생성 후 반환합니다.
     *
     * @param clazz 클래스 타입
     * @return 컴포넌트 인스턴스
     * */
    private static Object createComponentInstance(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        try {
            Constructor<?> constructor = getDefaultConstructor(clazz, constructors);
            Parameter[] constructorParameters = constructor.getParameters();
            List<Object> parameters = createParameters(constructorParameters);
            int differenceParameterNumber = constructorParameters.length - parameters.size();
            if (differenceParameterNumber < 0) {
                throw new BeanCreationException(clazz);
            }

            if (differenceParameterNumber > 0) {
                for (int i = 0; i < differenceParameterNumber; i++) {
                    parameters.add(null);
                }
            }
            return constructor.newInstance(parameters.toArray());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        throw new BeanCreationException(clazz);
    }

    /**
     * 빈 생성시 필요한 파라미터를 생성 후 반환합니다.
     *
     * @param parameters 생성자 파라미터 목록
     * @return 빈 목록
     * */
    private static List<Object> createParameters(Parameter[] parameters) {
        List<Object> parameterList = new ArrayList<>();
        for (Parameter parameter : parameters) {
            try {
                BeanInfo beanInfo = getBeanInfo(parameter);
                if (beanInfo == null) continue;
                parameterList.add(beanInfo.getInstance());
            } catch (BeanNotFoundException e) {
                e.printStackTrace();
            }
        }
        return parameterList;
    }

    /**
     * BeanInfo 인스턴스를 찾은 후 없다면 생성 후 반환합니다.
     *
     * @param parameter 파라미터
     * @return BeanInfo 인스턴스
     * */
    private static BeanInfo getBeanInfo(Parameter parameter) {
        String parameterName = parameter.getName();
        BeanInfo beanInfo = findBeanInfo(parameter.getType(), parameterName);
        if (beanInfo != null) return beanInfo;
        int index = BeanClassLoader.getComponentClasses().indexOf(parameter.getType());
        if (index == -1) return null;
        Class<?> beanClass = BeanClassLoader.getComponentClasses().get(index);
        String beanName = getBeanName(beanClass);
        Object beanInstance = createComponentInstance(beanClass);
        beanInfo = BeanInfo.of(beanName, beanInstance);
        addBeanMap(parameter.getType(), beanInstance, parameterName);
        return beanInfo;
    }

    /**
     * 빈 이름을 생성 후 반환합니다.
     *
     * @param componentType 컴포넌트 타입
     * @return 빈 이름
     * */
    private static String getBeanName(Class<?> componentType) {
        Qualifier qualifier = componentType.getDeclaredAnnotation(Qualifier.class);
        if (qualifier != null) {
            return qualifier.value();
        }
        String beanName = componentType.getSimpleName();
        return beanName.substring(0, 1).toLowerCase() + beanName.substring(1);
    }

    /**
     * 존재하는 빈이 있는 지 확인합니다.
     *
     * @param componentType 컴포넌트 타입
     * @return 중복 유무
     * */
    private static boolean existsBean(Class<?> componentType) {
        String beanName = getBeanName(componentType);
        List<BeanInfo> beanInfos = beanMap.get(componentType);
        if (beanInfos == null) return false;
        for (BeanInfo beanInfo : beanInfos) {
            if (beanInfo.getName().equals(beanName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 빈이 있는지 확인 후 없다면 생성하고 정보를 반환합니다.
     *
     * @param componentType 컴포넌트 타입
     * @param parameterName 파라미터 이름
     * @return 빈 정보
     * */
    private static BeanInfo findBeanInfo(Class<?> componentType, String parameterName) {
        if (!BeanClassLoader.getComponentClasses().contains(componentType)) {
            componentType = findSuperClass(componentType);
        }
        List<BeanInfo> beanInfos = beanMap.get(componentType);
        if (beanInfos == null) return null;
        if (beanInfos.size() == 1)
            return beanInfos.get(0);
        for (BeanInfo beanInfo : beanInfos) {
            if (!beanInfo.getName().equals(parameterName)) continue;
            return beanInfo;
        }
        return null;
    }

    /**
     * 컴포넌트 타입에 해당하는 빈이 존재하는 지 확인 후 있다면 키를 반환합니다.
     *
     * @param componentType 컴포넌트 타입
     * @return 빈 키
     * */
    private static Class<?> findSuperClass(Class<?> componentType) {
        Set<Class<?>> keys = beanMap.keySet();
        for (Class<?> key : keys) {
            if (!key.isAssignableFrom(componentType)) continue;
            return key;
        }
        return null;
    }

    /**
     * 기본 생성자를 반환합니다
     *
     * @param clazz 클래스 타입
     * @param constructors 생성자 목록
     * @return 기본 생성자
     * */
    private static Constructor<?> getDefaultConstructor(Class<?> clazz, Constructor<?>[] constructors) {
        if (constructors.length == 0) {
            try {
                return clazz.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
        }
        return constructors[0];
    }

    /**
     * 핸들러 빈 목록을 반환합니다.
     *
     * @return 핸들러 빈 목록
     * */
    public static List<Object> getHandlerBeans() {
        return handlerBeans;
    }

    /**
     * 인터셉터 구현체 인스턴스 목록을 반환합니다.
     *
     * @return 인터셉터 구현체 인스턴스
     * */
    public static List<Interceptor> getInterceptors() {
        return interceptors;
    }

    /**
     * 빈 목록을 반환합니다.
     *
     * @return 빈 목록
     * */
    public static Map<Class<?>, List<BeanInfo>> getBeanInfoMap() {
        return beanMap;
    }

    public static List<BeanInfo> getBeanInfoList(Class<?> type) {
        return beanMap.get(type);
    }

}
