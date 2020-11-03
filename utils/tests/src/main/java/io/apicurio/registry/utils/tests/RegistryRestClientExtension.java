package io.apicurio.registry.utils.tests;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.apicurio.registry.utils.IoUtil;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import io.apicurio.registry.client.RegistryRestClient;
import io.apicurio.registry.client.RegistryRestClientFactory;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.util.AnnotationUtils;

import static java.util.Collections.singletonList;

/**
 * @author famartin
 */
public class RegistryRestClientExtension implements TestTemplateInvocationContextProvider {

    private enum ParameterType {
        REGISTRY_SERVICE,
        UNSUPPORTED
    }

    private static ParameterType getParameterType(Type type) {
        if (type instanceof Class) {
            if (type == RegistryRestClient.class) {
                return ParameterType.REGISTRY_SERVICE;
            }
        } else if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type rawType = pt.getRawType();
            if (rawType == RegistryRestClient.class) {
                return ParameterType.REGISTRY_SERVICE;
            }
        }
        return ParameterType.UNSUPPORTED;
    }

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return context.getTestMethod().map(method -> {
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int i = 0; i < parameterTypes.length; i++) {
                if (getParameterType(method.getGenericParameterTypes()[i]) != ParameterType.UNSUPPORTED) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }



    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        AnnotationUtils.findAnnotation(context.getRequiredTestMethod(), RegistryRestClientTest.class)
                .orElseThrow(IllegalStateException::new); // should be there

        String registryUrl = TestUtils.getRegistryApiUrl();

        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.GLOBAL);

        List<TestTemplateInvocationContext> invocationCtxts = new ArrayList<>();

            RegistryServiceWrapper plain = store.getOrComputeIfAbsent(
                    "plain_client",
                    k -> new RegistryServiceWrapper(k, "registry_rest_client", registryUrl),
                    RegistryServiceWrapper.class
            );
            invocationCtxts.add(new RegistryServiceTestTemplateInvocationContext(plain, context.getRequiredTestMethod()));

        return invocationCtxts.stream();
    }

    private static class RegistryServiceTestTemplateInvocationContext implements TestTemplateInvocationContext, ParameterResolver {

        private RegistryServiceWrapper wrapper;
        private Method testMethod;
        private static RegistryRestClient CLIENT;

        public RegistryServiceTestTemplateInvocationContext(RegistryServiceWrapper wrapper, Method testMethod) {
            this.wrapper = wrapper;
            this.testMethod = testMethod;
        }

        @Override
        public String getDisplayName(int invocationIndex) {
                return testMethod.getName();
        }

        @Override
        public List<Extension> getAdditionalExtensions() {
            return singletonList(this);
        }

        @Override
        public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) throws ParameterResolutionException {
            Parameter parameter = pc.getParameter();
            return getParameterType(parameter.getParameterizedType()) != ParameterType.UNSUPPORTED;
        }

        @Override
        public Object resolveParameter(ParameterContext pc, ExtensionContext ec) throws ParameterResolutionException {
            Parameter parameter = pc.getParameter();
            ParameterType type = getParameterType(parameter.getParameterizedType());
            switch (type) {
                case REGISTRY_SERVICE: {
                    return (wrapper.service = getRestClient());
                }
                default:
                    throw new IllegalStateException("Invalid parameter type: " + type);
            }
        }

        private static final RegistryRestClient getRestClient() {
            if (CLIENT == null) {
                CLIENT = RegistryRestClientFactory.create(TestUtils.getRegistryApiUrl());
            }
            return CLIENT;
        }
    }

    private static class RegistryServiceWrapper implements ExtensionContext.Store.CloseableResource {
        private String key;
        private String method;
        private String registryUrl;
        private volatile AutoCloseable service;

        public RegistryServiceWrapper(String key, String method, String registryUrl) {
            this.key = key;
            this.method = method;
            this.registryUrl = registryUrl;
        }

        @Override
        public void close() throws Throwable {
            IoUtil.close(service);
        }
    }
}
