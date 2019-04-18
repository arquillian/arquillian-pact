package org.arquillian.algeron.pact.provider.core;

import au.com.dius.pact.model.Pact;
import au.com.dius.pact.model.PactReader;
import org.arquillian.algeron.pact.provider.api.Pacts;
import org.arquillian.algeron.pact.provider.spi.Consumer;
import org.arquillian.algeron.pact.provider.spi.Provider;
import org.arquillian.algeron.provider.core.AlgeronProviderConfiguration;
import org.arquillian.algeron.provider.spi.retriever.ContractsRetriever;
import org.arquillian.algeron.provider.spi.retriever.ContractsSource;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.arquillian.test.spi.annotation.SuiteScoped;
import org.jboss.arquillian.test.spi.event.suite.BeforeClass;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * Reads provided Pacts from defined @ContractsSource or any annotation meta-annotated with @ContractsSource
 * or configured in arquillian configuration file.
 */
public class PactsRetriever {

    public static final String PROVIDER = "provider";

    @Inject
    @SuiteScoped
    InstanceProducer<Pacts> pactsInstanceProducer;

    @Inject
    Instance<AlgeronProviderConfiguration> algeronProviderConfigurationInstance;

    public void retrievePacts(@Observes BeforeClass test) {
        List<Pact> pacts = getPacts(test);
        if (!pacts.isEmpty()) {
            pactsInstanceProducer.set(new Pacts(pacts));
        }
    }

    protected List<Pact> getPacts(BeforeClass test) {
        final TestClass testClass = test.getTestClass();

        final Provider providerInfo = testClass.getAnnotation(Provider.class);
        if (providerInfo == null) {
            return Collections.emptyList();
        }

        final String serviceName = providerInfo.value();

        final Consumer consumerInfo = testClass.getAnnotation(Consumer.class);
        final String consumerName = consumerInfo != null ? consumerInfo.value() : null;

        List<Pact> pacts;
        try {
            final ContractsRetriever contractsSource =
                getContractsSource(testClass, algeronProviderConfigurationInstance.get());
            contractsSource.setProviderName(serviceName);
            final List<URI> contractsDirectory = contractsSource.retrieve();

            Map<String, Object> options = new HashMap<>();
            if (algeronProviderConfigurationInstance.get() != null && algeronProviderConfigurationInstance.get().getRetrieverConfiguration() != null) {
                Map<String, Object> config = algeronProviderConfigurationInstance.get().getRetrieverConfiguration();
                // if pact retrieve specifies username, we need to create Pact options with authentication flag
                if (config.containsKey("username")) {
                    options.put("authentication", Arrays.asList("Basic", config.get("username"), config.get("password")));
                }
            }
            pacts = loadContractFiles(contractsDirectory, serviceName, options).stream()
                .filter(p -> consumerName == null || p.getConsumer().getName().equals(consumerName))
                .collect(toList());
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        return pacts;
    }

    protected List<Pact> loadContractFiles(List<URI> contracts, String providerName, Map<String, ?> options) {
        if (contracts != null) {
            List<URI> contractFiles = contracts.stream()
                .filter(uri -> uri.toString().endsWith(".json"))
                .collect(toList());

            List<Pact> result = new ArrayList<>();
            if (contractFiles != null) {
                return contractFiles.stream()
                    .map(URI::toString)
                    .map(uri -> PactReader.loadPact(options, uri))
                    .filter(pact -> pact.getProvider().getName().equals(providerName))
                    .collect(Collectors.toList());

            }
        }
        return new ArrayList<>();
    }

    protected ContractsRetriever getContractsSource(final TestClass testClass,
                                                    AlgeronProviderConfiguration algeronProviderConfiguration) {

        // Gets a test annotated directly with PactSource annotation
        final ContractsSource pactSource = testClass.getAnnotation(ContractsSource.class);

        // Gets annotations that might be meta annotated with PactSource annotation
        final List<Annotation> pactLoaders = Arrays.stream(testClass.getJavaClass().getAnnotations())
            .filter(annotation -> annotation.annotationType().getAnnotation(ContractsSource.class) != null)
            .collect(toList());

        // It can only be one PactSource in test
        if (isIncorrectNumberOfContractSources(pactSource, pactLoaders)) {
            throw new IllegalArgumentException(
                String.format("Exactly zero or one contract source as annotation should be set, but %s are set",
                    countNumberOfContractsSource(pactSource, pactLoaders)));
        }

        try {
            if (pactSource != null) {
                final Class<? extends ContractsRetriever> pactLoaderClass = pactSource.value();
                try {
                    // Checks if there is a constructor with one argument of type Class.
                    final Constructor<? extends ContractsRetriever> contructorWithClass =
                        pactLoaderClass.getDeclaredConstructor(Class.class);
                    contructorWithClass.setAccessible(true);
                    return contructorWithClass.newInstance(testClass.getJavaClass());
                } catch (NoSuchMethodException e) {
                    return pactLoaderClass.newInstance();
                }
            } else {
                if (isAnnotationPresent(pactLoaders)) {
                    final Annotation annotation = pactLoaders.iterator().next();
                    return annotation.annotationType().getAnnotation(ContractsSource.class).value()
                        .getConstructor(annotation.annotationType()).newInstance(annotation);
                } else {
                    return getContractsRetriever(algeronProviderConfiguration);
                }
            }
        } catch (final InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new IllegalStateException("Error while creating contracts source", e);
        }
    }

    private boolean isIncorrectNumberOfContractSources(ContractsSource pactSource, List<Annotation> pactLoaders) {
        return countNumberOfContractsSource(pactSource, pactLoaders) > 1;
    }

    private int countNumberOfContractsSource(ContractsSource pactSource, List<Annotation> pactLoaders) {
        return (pactSource == null ? 0 : 1) + pactLoaders.size();
    }

    private boolean isAnnotationPresent(List<Annotation> pactLoaders) {
        return pactLoaders.size() > 0;
    }

    private ContractsRetriever getContractsRetriever(AlgeronProviderConfiguration algeronProviderConfiguration) {
        if (algeronProviderConfiguration != null && algeronProviderConfiguration.isRetrieverConfigurationSet()) {
            final Map<String, Object> retrieverConfiguration = algeronProviderConfiguration.getRetrieverConfiguration();

            if (retrieverConfiguration.containsKey(PROVIDER)) {
                final ContractsRetriever contractRetriever =
                    findContractRetriever((String) retrieverConfiguration.get(PROVIDER));
                contractRetriever.configure(retrieverConfiguration);

                return contractRetriever;
            } else {
                throw new IllegalArgumentException(String.format(
                    "Retrieving contracts are enabled, but configuration is not providing a %s property with provider name to be used.",
                    PROVIDER));
            }
        }

        throw new IllegalArgumentException("No retriever defined as annotation nor in arquillian configuration file.");
    }

    private ContractsRetriever findContractRetriever(String name) {

        ServiceLoader<ContractsRetriever> serviceLoader = ServiceLoader.load(ContractsRetriever.class);

        for (ContractsRetriever contractsRetriever : serviceLoader) {
            if (contractsRetriever.getName().equals(name)) {
                return contractsRetriever;
            }
        }

        throw new IllegalArgumentException(String.format("No retriever registered with name %s.", name));
    }
}
