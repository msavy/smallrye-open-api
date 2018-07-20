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
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Type;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class FieldProcessor {
    private final WrappedIndexView index;
    private final DataObjectDeque objectStack;
    private final DataObjectDeque.PathEntry parentPathEntry;
    private final TypeResolver typeResolver;
    //private final FieldInfo fieldInfo;


    private final String fieldName;
    private final Type fieldType;
    private final ;

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
        //this.fieldInfo = fieldInfo;
        fieldName = fieldInfo.name();
        fieldType = fieldInfo.type();

        this.fieldSchema = new SchemaImpl();
    }

    public

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

        // Type could be replaced (e.g. generics). TODO too many args
        TypeProcessor typeProcessor = new TypeProcessor(index, objectStack, typeResolver, parentPathEntry, fieldInfo.type(), fieldSchema, fieldAnnotationInstance);

        Type postProcessedField = typeProcessor.processType();
        fieldSchema = typeProcessor.getSchema();

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

        TypeProcessor typeProcessor = new TypeProcessor(index, objectStack, typeResolver, parentPathEntry, fieldInfo.type(), fieldSchema, fieldAnnotationInstance);

        Type postProcessedField = typeProcessor.processType();
        fieldSchema = typeProcessor.getSchema();


        TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(postProcessedField);
        fieldSchema.setType(typeFormat.getSchemaType());

        if (typeFormat.getFormat().hasFormat()) {
            fieldSchema.setFormat(typeFormat.getFormat().format());
        }
    }

}
