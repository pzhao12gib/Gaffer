/*
 * Copyright 2016-2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.gchq.gaffer.jsonserialisation;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import com.google.common.collect.Sets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.gchq.gaffer.commonutil.iterable.CloseableIterable;
import uk.gov.gchq.gaffer.exception.SerialisationException;
import uk.gov.gchq.gaffer.jsonserialisation.jackson.CloseableIterableDeserializer;
import uk.gov.gchq.koryphe.impl.binaryoperator.StringDeduplicateConcat;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;

/**
 * A <code>JSONSerialiser</code> provides the ability to serialise and deserialise to/from JSON.
 * The serialisation is set to not include nulls or default values.
 * <p>
 * JSONSerialiser is a singleton. The behaviour of the {@link ObjectMapper}
 * can be configured by extending this class and configuring the ObjectMapper.
 * Child classes must has a default no argument constructor. You will then need
 * to set the gaffer.serialiser.json.class property in your StoreProperties or
 * as a System Property.
 * </p>
 * <p>
 * Once the singleton instance has been instantiated it will not be updated,
 * unless update() or update(jsonSerialiserClass) is called. An update will
 * be done automatically in the REST API when it is first initialised and
 * also when a Store is initialised.
 * </p>
 */
public class JSONSerialiser {
    public static final String JSON_SERIALISER_CLASS_KEY = "gaffer.serialiser.json.class";
    /**
     * CSV of {@link JSONSerialiserModules} class names. These modules will
     * be added to the {@link ObjectMapper}.
     */
    public static final String JSON_SERIALISER_MODULES = "gaffer.serialiser.json.modules";
    public static final String DEFAULT_SERIALISER_CLASS_NAME = JSONSerialiser.class.getName();

    public static final String FILTER_FIELDS_BY_NAME = "filterFieldsByName";

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final Logger LOGGER = LoggerFactory.getLogger(JSONSerialiser.class);

    private static JSONSerialiser instance;

    private final ObjectMapper mapper;

    /**
     * Constructs a <code>JSONSerialiser</code> that skips nulls and default values.
     */
    protected JSONSerialiser() {
        this(createDefaultMapper());
    }

    /**
     * Constructs a <code>JSONSerialiser</code> with a custom {@link ObjectMapper}.
     * To create the custom ObjectMapper it is advised that you start with the
     * default mapper provided from JSONSerialiser.createDefaultMapper() then
     * add your custom configuration.
     *
     * @param mapper a custom object mapper
     */
    protected JSONSerialiser(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Child classes of this JSONSerialiser can call this method to register
     * custom modules.
     *
     * @param modules the {@link ObjectMapper} modules to register
     */
    protected void registerModules(final Module... modules) {
        for (final Module module : modules) {
            mapper.registerModule(module);
        }
    }

    /**
     * Child classes of this JSONSerialiser can call this method to register
     * custom modules.
     *
     * @param modules the {@link ObjectMapper} modules to register
     */
    protected void registerModules(final Collection<Module> modules) {
        modules.forEach(mapper::registerModule);
    }

    public static void updateInstance(final String jsonSerialiserClass, final String jsonSerialiserModules) {
        if (StringUtils.isNotBlank(jsonSerialiserModules)) {
            final String modulesCsv = new StringDeduplicateConcat().apply(
                    System.getProperty(JSON_SERIALISER_MODULES),
                    jsonSerialiserModules
            );
            System.setProperty(JSON_SERIALISER_MODULES, modulesCsv);
        }

        if (null != jsonSerialiserClass) {
            System.setProperty(JSON_SERIALISER_CLASS_KEY, jsonSerialiserClass);
            _updateInstance(jsonSerialiserClass);
        } else {
            updateInstance();
        }
    }

    public static void updateInstance() {
        _updateInstance(System.getProperty(JSON_SERIALISER_CLASS_KEY, DEFAULT_SERIALISER_CLASS_NAME));
    }

    public static ObjectMapper createDefaultMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(SerializationFeature.CLOSE_CLOSEABLE, true);
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        mapper.registerModule(getCloseableIterableDeserialiserModule());

        // Using the deprecated version for compatibility with older versions of jackson
        mapper.registerModule(new JSR310Module());

        // Use the 'setFilters' method so it is compatible with older versions of jackson
        mapper.setFilters(getFilterProvider());
        return mapper;
    }

    public static FilterProvider getFilterProvider(final String... fieldsToExclude) {
        if (null == fieldsToExclude || fieldsToExclude.length == 0) {
            // Use the 'serializeAllExcept' method so it is compatible with older versions of jackson
            return new SimpleFilterProvider()
                    .addFilter(FILTER_FIELDS_BY_NAME, (BeanPropertyFilter) SimpleBeanPropertyFilter.serializeAllExcept());
        }

        return new SimpleFilterProvider()
                .addFilter(FILTER_FIELDS_BY_NAME, (BeanPropertyFilter) SimpleBeanPropertyFilter.serializeAllExcept(fieldsToExclude));
    }

    /**
     * @param clazz the clazz of the object to be serialised/deserialised
     * @return true if the clazz can be serialised/deserialised
     */
    public static boolean canHandle(final Class clazz) {
        return getInstance().mapper.canSerialize(clazz);
    }

    /**
     * Serialises an object.
     *
     * @param object          the object to be serialised
     * @param fieldsToExclude optional property names to exclude from the json
     * @return the provided object serialised into bytes
     * @throws SerialisationException if the object fails to be serialised
     */
    public static byte[] serialise(final Object object, final String... fieldsToExclude) throws SerialisationException {
        return serialise(object, false, fieldsToExclude);
    }


    /**
     * Serialises an object.
     *
     * @param object          the object to be serialised
     * @param prettyPrint     true if the object should be serialised with pretty printing
     * @param fieldsToExclude optional property names to exclude from the json
     * @return the provided object serialised (with pretty printing) into bytes
     * @throws SerialisationException if the object fails to serialise
     */
    public static byte[] serialise(final Object object, final boolean prettyPrint, final String... fieldsToExclude) throws SerialisationException {
        final ByteArrayBuilder byteArrayBuilder = new ByteArrayBuilder();
        try {
            serialise(object, JSON_FACTORY.createGenerator(byteArrayBuilder, JsonEncoding.UTF8), prettyPrint, fieldsToExclude);
        } catch (final IOException e) {
            throw new SerialisationException(e.getMessage(), e);
        }

        return byteArrayBuilder.toByteArray();
    }

    /**
     * Serialises an object using the provided json generator.
     *
     * @param object          the object to be serialised and written to file
     * @param jsonGenerator   the {@link com.fasterxml.jackson.core.JsonGenerator} to use to write the object to
     * @param prettyPrint     true if the object should be serialised with pretty printing
     * @param fieldsToExclude optional property names to exclude from the json
     * @throws SerialisationException if the object fails to serialise
     */
    public static void serialise(final Object object, final JsonGenerator jsonGenerator, final boolean prettyPrint, final String... fieldsToExclude)
            throws SerialisationException {
        if (prettyPrint) {
            jsonGenerator.useDefaultPrettyPrinter();
        }

        final ObjectWriter writer = getInstance().mapper.writer(getFilterProvider(fieldsToExclude));
        try {
            writer.writeValue(jsonGenerator, object);
        } catch (final IOException e) {
            throw new SerialisationException("Failed to serialise object to json: " + e.getMessage(), e);
        }
    }

    /**
     * @param bytes the bytes of the object to deserialise
     * @param clazz the class of the object to deserialise
     * @param <T>   the type of the object
     * @return the deserialised object
     * @throws SerialisationException if the bytes fail to deserialise
     */
    public static <T> T deserialise(final byte[] bytes, final Class<T> clazz) throws SerialisationException {
        try {
            return getInstance().mapper.readValue(bytes, clazz);
        } catch (final IOException e) {
            throw new SerialisationException(e.getMessage(), e);
        }
    }

    /**
     * @param stream the {@link java.io.InputStream} containing the bytes of the object to deserialise
     * @param clazz  the class of the object to deserialise
     * @param <T>    the type of the object
     * @return the deserialised object
     * @throws SerialisationException if the bytes fail to deserialise
     */
    public static <T> T deserialise(final InputStream stream, final Class<T> clazz) throws SerialisationException {
        try (final InputStream stream2 = stream) {
            final byte[] bytes = IOUtils.toByteArray(stream2);
            return deserialise(bytes, clazz);
        } catch (final IOException e) {
            throw new SerialisationException(e.getMessage(), e);
        }
    }

    /**
     * @param bytes the bytes of the object to deserialise
     * @param type  the type reference of the object to deserialise
     * @param <T>   the type of the object
     * @return the deserialised object
     * @throws SerialisationException if the bytes fail to deserialise
     */
    public static <T> T deserialise(final byte[] bytes, final TypeReference<T> type) throws SerialisationException {
        try {
            return getInstance().mapper.readValue(bytes, type);
        } catch (final IOException e) {
            throw new SerialisationException(e.getMessage(), e);
        }
    }

    /**
     * @param stream the {@link java.io.InputStream} containing the bytes of the object to deserialise
     * @param type   the type reference of the object to deserialise
     * @param <T>    the type of the object
     * @return the deserialised object
     * @throws SerialisationException if the bytes fail to deserialise
     */
    public static <T> T deserialise(final InputStream stream, final TypeReference<T> type) throws SerialisationException {
        try (final InputStream stream2 = stream) {
            final byte[] bytes = IOUtils.toByteArray(stream2);
            return deserialise(bytes, type);
        } catch (final IOException e) {
            throw new SerialisationException(e.getMessage(), e);
        }
    }

    /**
     * @param content the {@link java.lang.String} containing the bytes of the object to deserialise
     * @return the deserialised object
     * @throws SerialisationException if the bytes fail to deserialise
     */
    public static JsonNode getJsonNodeFromString(final String content) throws SerialisationException {
        try {
            return getInstance().mapper.readTree(content);
        } catch (IOException e) {
            throw new SerialisationException(e.getMessage(), e);
        }
    }

    @JsonIgnore
    public static ObjectMapper getMapper() {
        return getInstance().mapper;
    }

    private static JSONSerialiser getInstance() {
        if (null == instance) {
            updateInstance();
        }
        return instance;
    }

    private static void _updateInstance(final String jsonSerialiserClass) {
        final JSONSerialiser newInstance;
        if (null == jsonSerialiserClass) {
            newInstance = new JSONSerialiser();
        } else {
            try {
                newInstance = Class.forName(jsonSerialiserClass).asSubclass(JSONSerialiser.class).newInstance();
            } catch (final InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                throw new IllegalArgumentException("Property " + JSON_SERIALISER_CLASS_KEY + " must be set to a class that is a sub class of " + JSONSerialiser.class.getName() + ". This class is not valid: " + jsonSerialiserClass, e);
            }
        }

        final String moduleFactories = System.getProperty(JSON_SERIALISER_MODULES, "");
        final Set<String> factoryClasses = Sets.newHashSet(moduleFactories.split(","));
        factoryClasses.remove("");
        for (final String factoryClass : factoryClasses) {
            final JSONSerialiserModules factory;
            try {
                factory = Class.forName(factoryClass).asSubclass(JSONSerialiserModules.class).newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                throw new IllegalArgumentException("Property " + JSON_SERIALISER_MODULES + " must be set to a csv of classes that are a sub class of " + JSONSerialiserModules.class.getName() + ". These classes are not valid: " + factoryClass, e);
            }
            newInstance.mapper.registerModules(factory.getModules());
        }
        instance = newInstance;
        LOGGER.debug("Updated json serialiser to use: {}, and modules: {}", jsonSerialiserClass, moduleFactories);
    }

    private static SimpleModule getCloseableIterableDeserialiserModule() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(CloseableIterable.class, new CloseableIterableDeserializer());
        return module;
    }
}
