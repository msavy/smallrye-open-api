/*
 * Copyright 2018 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.openapi.runtime.scanner.indexwrapper;

import io.smallrye.openapi.api.models.media.SchemaImpl;
import io.smallrye.openapi.runtime.scanner.TypeResolver;
import io.smallrye.openapi.runtime.util.TypeUtil;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ArrayType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.ARRAY_TYPE_OBJECT;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.COLLECTION_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.ENUM_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.MAP_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.OBJECT_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.STRING_TYPE;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class TypeProcessor {
    private static final Logger LOG = Logger.getLogger(TypeProcessor.class);

    private final Schema schema;
    private final WrappedIndexView index;
    private final AnnotationInstance annotationInstance;
    private final DataObjectDeque objectStack;
    private final TypeResolver typeResolver;
    private final DataObjectDeque.PathEntry parentPathEntry;
    private Type type;

    public TypeProcessor(WrappedIndexView index,
                         DataObjectDeque objectStack,
                         TypeResolver typeResolver,
                         DataObjectDeque.PathEntry parentPathEntry,
                         Type type,
                         Schema schema,
                         AnnotationInstance annotationInstance) {
        this.objectStack = objectStack;
        this.typeResolver = typeResolver;
        this.parentPathEntry = parentPathEntry;
        this.type = type;
        this.schema = schema;
        this.index = index;
        this.annotationInstance = annotationInstance;
    }

    public Schema getSchema() {
        return schema;
    }

    public Type processType() {
        // If it's a terminal type.
        if (isTerminalType(type)) {
            return type;
        }

        if (type.kind() == Type.Kind.WILDCARD_TYPE) {
            type = TypeUtil.resolveWildcard(type.asWildcardType());
        }

        if (type.kind() == Type.Kind.ARRAY) {
            LOG.infov("Processing an array {0}", type);
            ArrayType arrayType = type.asArrayType();

            // TODO handle multi-dimensional arrays.

            // Array-type schema
            SchemaImpl arrSchema = new SchemaImpl();
            schema.type(Schema.SchemaType.ARRAY);
            schema.items(arrSchema);

            // Only use component (excludes the special name formatting for arrays).
            TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(arrayType.component());
            arrSchema.setType(typeFormat.getSchemaType());
            arrSchema.setFormat(typeFormat.getFormat().format());

            // If it's not a terminal type, then push for later inspection.
            if (!isTerminalType(arrayType.component()) && index.containsClass(type)) {
                ClassInfo resolvedKlazz = index.getClass(type);
                pushToStack(type, resolvedKlazz, arrSchema);
            }
            return arrayType;
        }

        if (isA(type, ENUM_TYPE) && index.containsClass(type)) {
            LOG.infov("Processing an enum {0}", type);
            ClassInfo enumKlazz = index.getClass(type);

            for (FieldInfo enumField : enumKlazz.fields()) {
                // Ignore the hidden enum array as it's not accessible. Add fields that look like enums (of type enumKlazz)
                // NB: Eclipse compiler and OpenJDK compiler have different names for this field.
                if (!enumField.name().endsWith("$VALUES") && TypeUtil.getName(enumField.type()).equals(enumKlazz.name())) {
                    // Enum's value fields.
                    schema.addEnumeration(enumField.name());
                }
            }
            return STRING_TYPE;
        }

        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            // Parameterised type (e.g. Foo<A, B>)
            //return readParamType(annotationTarget, pathEntry, schema, fieldType.asParameterizedType(), typeResolver);
            return readParameterizedType(type.asParameterizedType());
        }

        if (type.kind() == Type.Kind.TYPE_VARIABLE ||
                type.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE) {
            // Resolve type variable to real variable.
            //return resolveTypeVariable(annotationTarget, typeResolver, schema, pathEntry, fieldType);
            return resolveTypeVariable(schema, type);
        }

        // Raw Collection
        if (isA(type, COLLECTION_TYPE)) {
            return ARRAY_TYPE_OBJECT;
        }

        // Raw Map
        if (isA(type, MAP_TYPE)) {
            return OBJECT_TYPE;
        }

        // Simple case: bare class or primitive type.
        if (index.containsClass(type)) {
            pushToStack(type);
        } else {
            // If the type is not in Jandex then we don't have easy access to it.
            // Future work could consider separate code to traverse classes reachable from this classloader.
            LOG.infov("Encountered type not in Jandex index that is not well-known type. " +
                    "Will not traverse it: {0}", type);
        }

        return type;
    }

    private Type readParameterizedType(ParameterizedType pType) {
        LOG.infov("Processing parameterized type {0}", pType);

        // If it's a collection, we should treat it as an array.
        if (isA(pType, COLLECTION_TYPE)) { // TODO maybe also Iterable?
            LOG.infov("Processing Java Collection. Will treat as an array.");
            SchemaImpl arraySchema = new SchemaImpl();
            schema.type(Schema.SchemaType.ARRAY);
            schema.items(arraySchema);

            // Should only have one arg for collection.
            Type arg = pType.arguments().get(0);

            if (isTerminalType(arg)) {
                TypeUtil.TypeWithFormat terminalType = TypeUtil.getTypeFormat(arg);
                arraySchema.type(terminalType.getSchemaType());
                arraySchema.format(terminalType.getFormat().format());
            } else {
                resolveParameterizedType(arg, arraySchema);
            }
            return ARRAY_TYPE_OBJECT; // Representing collection as JSON array
        } else if (isA(pType, MAP_TYPE)) {
            LOG.infov("Processing Map. Will treat as an object.");
            schema.type(Schema.SchemaType.OBJECT);

            if (pType.arguments().size() == 2) {
                Type valueType = pType.arguments().get(1);
                SchemaImpl propsSchema = new SchemaImpl();
                if (isTerminalType(valueType)) {
                    TypeUtil.TypeWithFormat tf = TypeUtil.getTypeFormat(valueType);
                    propsSchema.setType(tf.getSchemaType());
                    propsSchema.setFormat(tf.getFormat().format());
                } else {
                    resolveParameterizedType(valueType, propsSchema);
                }
                // Add properties schema to field schema.
                schema.additionalProperties(propsSchema);
            }
            return OBJECT_TYPE;
        } else {
            // TODO is a index.contains check necessary?
            // This type will be resolved later, if necessary.
            pushToStack(pType);
            return pType;
        }
    }

    private void resolveParameterizedType(Type valueType, SchemaImpl propsSchema) {
        if (valueType.kind() == Type.Kind.TYPE_VARIABLE ||
                valueType.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE ||
                valueType.kind() == Type.Kind.WILDCARD_TYPE) {
            Type resolved = resolveTypeVariable(propsSchema, valueType);
            if (index.containsClass(resolved)) {
                propsSchema.type(Schema.SchemaType.OBJECT);
            }
        } else if (index.containsClass(valueType)) {
            propsSchema.type(Schema.SchemaType.OBJECT);
            pushToStack(valueType, propsSchema);
        }
    }

    private Type resolveTypeVariable(Schema schema, Type fieldType) {
        // Type variable (e.g. A in Foo<A>)
        Type resolvedType = typeResolver.getResolvedType(fieldType);

        LOG.infov("Resolved type {0} -> {1}", fieldType, resolvedType);
        if (isTerminalType(resolvedType) || !index.containsClass(resolvedType)) {
            LOG.infov("Is a terminal type {0}", resolvedType);
            TypeUtil.TypeWithFormat replacement = TypeUtil.getTypeFormat(resolvedType);
            schema.setType(replacement.getSchemaType());
            schema.setFormat(replacement.getFormat().format());
        } else {
            LOG.infov("Attempting to do TYPE_VARIABLE substitution: {0} -> {1}", fieldType, resolvedType);

            if (index.containsClass(resolvedType)) {
                // TODO: Cycle detection incorrectly sees cycle in same-class nested generic types (but with non-cyclic generic args).
                // TODO: e.g. Foo<String, Foo<Integer, Integer> is not automatically a cycle.
                // TODO: This could be fixed by factoring generic args
                ClassInfo klazz = index.getClass(resolvedType);
                DataObjectDeque.PathEntry entry = objectStack.leafNode(parentPathEntry, annotationInstance, klazz, resolvedType, schema);
                objectStack.push(entry);
            } else {
                LOG.infov("Class for type {0} not available", resolvedType);
            }
        }
        return resolvedType;
    }

    private void pushToStack(Type fieldType) {
        LOG.info("pushing " + fieldType);
        objectStack.pushField(annotationInstance, parentPathEntry, fieldType, schema);
    }

    // TODO remove ClassInfo?
    private void pushToStack(Type resolvedType, ClassInfo resolvedKlazz, Schema schema) {
        objectStack.pushPathPair(annotationInstance, parentPathEntry, resolvedType, resolvedKlazz, schema);
    }

    // TODO remove ClassInfo?
    private void pushToStack(Type resolvedType, Schema schema) {
        ClassInfo resolvedKlazz = index.getClass(resolvedType);
        objectStack.pushPathPair(annotationInstance, parentPathEntry, resolvedType, resolvedKlazz, schema);
    }

    private boolean isA(Type testSubject, Type test) {
        return TypeUtil.isA(index, testSubject, test);
    }

    private boolean isTerminalType(Type type) {
        if (type.kind() == Type.Kind.TYPE_VARIABLE ||
                type.kind() == Type.Kind.WILDCARD_TYPE ||
                type.kind() == Type.Kind.ARRAY) {
            return false;
        }

        if (type.kind() == Type.Kind.PRIMITIVE ||
                type.kind() == Type.Kind.VOID) {
            return true;
        }

        TypeUtil.TypeWithFormat tf = TypeUtil.getTypeFormat(type);
        // If is known type.
        return tf.getSchemaType() != Schema.SchemaType.OBJECT &&
                tf.getSchemaType() != Schema.SchemaType.ARRAY;
    }
}
