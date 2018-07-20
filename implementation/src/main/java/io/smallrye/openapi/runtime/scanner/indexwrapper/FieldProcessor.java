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

import io.smallrye.openapi.api.OpenApiConstants;
import io.smallrye.openapi.api.models.media.SchemaImpl;
import io.smallrye.openapi.runtime.scanner.TypeResolver;
import io.smallrye.openapi.runtime.util.JandexUtil;
import io.smallrye.openapi.runtime.util.SchemaFactory;
import io.smallrye.openapi.runtime.util.TypeUtil;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ArrayType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.ARRAY_TYPE_OBJECT;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.COLLECTION_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.ENUM_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.MAP_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.OBJECT_TYPE;
import static io.smallrye.openapi.runtime.scanner.OpenApiDataObjectScanner.STRING_TYPE;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class FieldProcessor {
    private final WrappedIndexView index;
    private final DataObjectDeque objectStack;
    private final DataObjectDeque.PathEntry parentPathEntry;
    private final TypeResolver typeResolver;
    private final FieldInfo fieldInfo;

    // This can be overridden.
    private Schema fieldSchema;
    // May be null if field is unannotated.
    private AnnotationInstance fieldAnnotationInstance;


    public FieldProcessor(WrappedIndexView index,
                          DataObjectDeque objectStack,
                          TypeResolver typeResolver,
                          DataObjectDeque.PathEntry parentPathEntry,
                          FieldInfo fieldInfo) {
        this.index = index;
        this.objectStack = objectStack;
        this.parentPathEntry = parentPathEntry;
        this.typeResolver = typeResolver;
        this.fieldInfo = fieldInfo;
        this.fieldSchema = new SchemaImpl();
    }

    public static Schema process(WrappedIndexView index,
                          DataObjectDeque path,
                          TypeResolver resolver,
                          DataObjectDeque.PathEntry currentPathEntry,
                          FieldInfo field) {
        FieldProcessor fp = new FieldProcessor(index, path, resolver, currentPathEntry, field);
        return fp.processField();
    }
//
//    public static Schema process(WrappedIndexView index,
//                                 DataObjectDeque path,
//                                 TypeResolver resolver,
//                                 DataObjectDeque.PathEntry currentPathEntry,
//                                 Type type) {
//        FieldProcessor fp = new FieldProcessor(index, path, resolver, currentPathEntry, index.getClass(type));
//        return fp.processField();
////        return fp.fieldSchema;
//    }

    public Schema processField() {
        fieldAnnotationInstance = TypeUtil.getSchemaAnnotation(fieldInfo);

        if (fieldAnnotationInstance != null) {
            // 1. Handle field annotated with @Schema.
            readSchemaAnnotatedField(fieldAnnotationInstance);
        } else {
            // 2. Handle unannotated field and just do simple inference.
            readUnannotatedField();
        }

        parentPathEntry.getSchema().addProperty(fieldInfo.name(), fieldSchema);
        return fieldSchema;
    }

    private void readSchemaAnnotatedField(@NotNull AnnotationInstance annotation) {
        if (annotation == null) {
            throw new IllegalArgumentException("Annotation must not be null");
        }

        //LOG.debugv("Processing @Schema annotation {0} on a field {1}", annotation, name);

        // Schemas can be hidden. Skip if that's the case.
        Boolean isHidden = JandexUtil.booleanValue(annotation, OpenApiConstants.PROP_HIDDEN);
        if (isHidden != null && isHidden == Boolean.TRUE) {
            return;
        }

        // If "required" attribute is on field. It should be applied to the *parent* schema.
        // Required is false by default.
        if (JandexUtil.booleanValueWithDefault(annotation, OpenApiConstants.PROP_REQUIRED)) {
            parentPathEntry.getSchema().addRequired(fieldInfo.name());
        }

        // Type could be replaced (e.g. generics).
        Type postProcessedField = processType();

        // TypeFormat pair contains mappings for Java <-> OAS types and formats.
        TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(postProcessedField);

        // Provide inferred type and format if relevant.
        Map<String, Object> overrides = new HashMap<>();
        overrides.put(OpenApiConstants.PROP_TYPE, typeFormat.getSchemaType());
        overrides.put(OpenApiConstants.PROP_FORMAT, typeFormat.getFormat().format());
        // readSchema *may* replace the existing schema, so we must assign.
        this.fieldSchema = SchemaFactory.readSchema(index, fieldSchema, annotation, overrides);
    }

    private void readUnannotatedField() {
        //LOG.debugv("Processing unannotated field {0}", type);

        Type processedType = processType();

        TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(processedType);
        fieldSchema.setType(typeFormat.getSchemaType());

        if (typeFormat.getFormat().hasFormat()) {
            fieldSchema.setFormat(typeFormat.getFormat().format());
        }
    }

    private Type processType() {
        Type fieldType = fieldInfo.type();

        // If it's a terminal type.
        if (isTerminalType(fieldInfo.type())) {
            return fieldType;
        }

        if (fieldType.kind() == Type.Kind.WILDCARD_TYPE) {
            fieldType = TypeUtil.resolveWildcard(fieldType.asWildcardType());
        }

        if (fieldType.kind() == Type.Kind.ARRAY) {
            //LOG.debugv("Processing an array {0}", fieldType);
            ArrayType arrayType = fieldType.asArrayType();

            // TODO handle multi-dimensional arrays.

            // Array-type schema
            SchemaImpl arrSchema = new SchemaImpl();
            fieldSchema.type(Schema.SchemaType.ARRAY);
            fieldSchema.items(arrSchema);

            // Only use component (excludes the special name formatting for arrays).
            TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(arrayType.component());
            arrSchema.setType(typeFormat.getSchemaType());
            arrSchema.setFormat(typeFormat.getFormat().format());

            // If it's not a terminal type, then push for later inspection.
            if (!isTerminalType(arrayType.component()) && index.containsClass(fieldType)) {
                ClassInfo resolvedKlazz = index.getClass(fieldType);
                pushField(fieldType, resolvedKlazz, arrSchema);
            }
            return arrayType;
        }

        if (isA(fieldType, ENUM_TYPE) && index.containsClass(fieldType)) {
            //LOG.debugv("Processing an enum {0}", fieldType);
            ClassInfo enumKlazz = index.getClass(fieldType);

            for (FieldInfo enumField : enumKlazz.fields()) {
                // Ignore the hidden enum array as it's not accessible. Add fields that look like enums (of type enumKlazz)
                // NB: Eclipse compiler and OpenJDK compiler have different names for this field.
                if (!enumField.name().endsWith("$VALUES") && TypeUtil.getName(enumField.type()).equals(enumKlazz.name())) {
                    // Enum's value fields.
                    fieldSchema.addEnumeration(enumField.name());
                }
            }
            return STRING_TYPE;
        }

        if (fieldType.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            // Parameterised type (e.g. Foo<A, B>)
            //return readParamType(annotationTarget, pathEntry, schema, fieldType.asParameterizedType(), typeResolver);
            return readParameterizedType(fieldType.asParameterizedType());
        }

        if (fieldType.kind() == Type.Kind.TYPE_VARIABLE ||
                fieldType.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE) {
            // Resolve type variable to real variable.
            //return resolveTypeVariable(annotationTarget, typeResolver, schema, pathEntry, fieldType);
            resolveTypeVariable(fieldSchema, fieldType);
        }

        // Raw Collection
        if (isA(fieldType, COLLECTION_TYPE)) {
            return ARRAY_TYPE_OBJECT;
        }

        // Raw Map
        if (isA(fieldType, MAP_TYPE)) {
            return OBJECT_TYPE;
        }

        // Simple case: bare class or primitive type.
        if (index.containsClass(fieldType)) {
            pushField(fieldType);
        } else {
            // If the type is not in Jandex then we don't have easy access to it.
            // Future work could consider separate code to traverse classes reachable from this classloader.
//            LOG.debugv("Encountered type not in Jandex index that is not well-known type. " +
//                    "Will not traverse it: {0}", fieldType);
        }

        return fieldType;
    }

    private Type readParameterizedType(ParameterizedType pType) {
        //LOG.debugv("Processing parameterized type {0}", pType);

        // If it's a collection, we should treat it as an array.
        if (isA(pType, COLLECTION_TYPE)) { // TODO maybe also Iterable?
            //LOG.debugv("Processing Java Collection. Will treat as an array.");
            SchemaImpl arraySchema = new SchemaImpl();
            fieldSchema.type(Schema.SchemaType.ARRAY);
            fieldSchema.items(arraySchema);

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
            //LOG.debugv("Processing Map. Will treat as an object.");
            fieldSchema.type(Schema.SchemaType.OBJECT);

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
                fieldSchema.additionalProperties(propsSchema);
            }
            return OBJECT_TYPE;
        } else {
            // TODO is a index.contains check necessary?
            // This type will be resolved later, if necessary.
            pushField(pType);
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
            pushField(valueType, propsSchema);
        }
    }

    private Type resolveTypeVariable(Schema schema, Type fieldType) {
        // Type variable (e.g. A in Foo<A>)
        Type resolvedType = typeResolver.getResolvedType(fieldType);

        //LOG.debugv("Resolved type {0} -> {1}", fieldType, resolvedType);
        if (isTerminalType(resolvedType) || index.containsClass(resolvedType)) {
            //LOG.tracev("Is a terminal type {0}", resolvedType);
            TypeUtil.TypeWithFormat replacement = TypeUtil.getTypeFormat(resolvedType);
            schema.setType(replacement.getSchemaType());
            schema.setFormat(replacement.getFormat().format());
        } else {
            //LOG.debugv("Attempting to do TYPE_VARIABLE substitution: {0} -> {1}", fieldType, resolvedType);

            if (index.containsClass(resolvedType)) {
                pushField(resolvedType);
            } else {
                //LOG.debugv("Class for type {0} not available", resolvedType);
            }
        }
        return resolvedType;
    }


    private void pushField(Type fieldType) {
        objectStack.pushField(fieldAnnotationInstance, parentPathEntry, fieldType, fieldSchema);
    }

    // TODO remove ClassInfo?
    private void pushField(Type resolvedType, ClassInfo resolvedKlazz, Schema schema) {
        objectStack.pushPathPair(fieldAnnotationInstance, parentPathEntry, resolvedType, resolvedKlazz, schema);
    }

    // TODO remove ClassInfo?
    private void pushField(Type resolvedType, Schema schema) {
        ClassInfo resolvedKlazz = index.getClass(resolvedType);
        objectStack.pushPathPair(fieldAnnotationInstance, parentPathEntry, resolvedType, resolvedKlazz, schema);
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
