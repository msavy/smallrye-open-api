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
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class FieldProcessor {
    private static final Logger LOG = Logger.getLogger(FieldProcessor.class);

    private final WrappedIndexView index;
    private final DataObjectDeque objectStack;
    private final DataObjectDeque.PathEntry parentPathEntry;
    private final TypeResolver typeResolver;
    private final String fieldName;
    private final Type fieldType;

    // This can be overridden.
    private Schema fieldSchema;
    // May be null if field is unannotated.
    private AnnotationTarget annotationTarget;

    public FieldProcessor(WrappedIndexView index,
                          DataObjectDeque objectStack,
                          DataObjectDeque.PathEntry parentPathEntry,
                          TypeResolver typeResolver,
                          AnnotationTarget annotationTarget,
                          String entityName,
                          Type entityType) {
        this.index = index;
        this.objectStack = objectStack;
        this.parentPathEntry = parentPathEntry;
        this.typeResolver = typeResolver;
        this.fieldName = entityName;
        this.fieldType = entityType;
        this.annotationTarget = annotationTarget;
        this.fieldSchema = new SchemaImpl();
    }

    public FieldProcessor(WrappedIndexView index,
                          DataObjectDeque objectStack,
                          TypeResolver typeResolver,
                          DataObjectDeque.PathEntry parentPathEntry,
                          FieldInfo fieldInfo) {
        this(index, objectStack, parentPathEntry, typeResolver, fieldInfo, fieldInfo.name(), fieldInfo.type());
    }

    public FieldProcessor(WrappedIndexView index,
                          DataObjectDeque objectStack,
                          TypeResolver typeResolver,
                          DataObjectDeque.PathEntry parentPathEntry,
                          Type type) {
        this(index, objectStack, parentPathEntry, typeResolver, index.getClass(type), type.name().toString(), type);
    }

    public static Schema process(WrappedIndexView index,
                          DataObjectDeque path,
                          TypeResolver resolver,
                          DataObjectDeque.PathEntry parentPathEntry,
                          FieldInfo field) {
        FieldProcessor fp = new FieldProcessor(index, path, resolver, parentPathEntry, field);
        return fp.processField();
    }

    public Schema processField() {
        AnnotationInstance schemaAnnotation = TypeUtil.getSchemaAnnotation(annotationTarget);

        if (schemaAnnotation == null && shouldInferUnannotatedFields()) {
            // Handle unannotated field and just do simple inference.
            readUnannotatedField();
        } else {
            // Handle field annotated with @Schema.
            readSchemaAnnotatedField(schemaAnnotation);
        }
        parentPathEntry.getSchema().addProperty(fieldName, fieldSchema);
        return fieldSchema;
    }

    private void readSchemaAnnotatedField(@NotNull AnnotationInstance annotation) {
        if (annotation == null) {
            throw new IllegalArgumentException("Annotation must not be null");
        }

        LOG.infov("Processing @Schema annotation {0} on a field {1}", annotation, fieldName);

        // Schemas can be hidden. Skip if that's the case.
        Boolean isHidden = JandexUtil.booleanValue(annotation, OpenApiConstants.PROP_HIDDEN);
        if (isHidden != null && isHidden == Boolean.TRUE) {
            return;
        }

        // If "required" attribute is on field. It should be applied to the *parent* schema.
        // Required is false by default.
        if (JandexUtil.booleanValueWithDefault(annotation, OpenApiConstants.PROP_REQUIRED)) {
            parentPathEntry.getSchema().addRequired(fieldName);
        }

        // Type could be replaced (e.g. generics). TODO too many args
        TypeProcessor typeProcessor = new TypeProcessor(index, objectStack, parentPathEntry, typeResolver, fieldType, fieldSchema, annotationTarget);

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
        LOG.infov("Processing unannotated field {0}", fieldType);

        TypeProcessor typeProcessor = new TypeProcessor(index, objectStack, parentPathEntry, typeResolver, fieldType, fieldSchema, annotationTarget);

        Type postProcessedField = typeProcessor.processType();
        fieldSchema = typeProcessor.getSchema();

        TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(postProcessedField);
        fieldSchema.setType(typeFormat.getSchemaType());

        if (typeFormat.getFormat().hasFormat()) {
            fieldSchema.setFormat(typeFormat.getFormat().format());
        }
    }

    private boolean shouldInferUnannotatedFields() {
        String infer = System.getProperties().getProperty("openapi.infer-unannotated-types", "true");
        return Boolean.parseBoolean(infer);
    }
}
