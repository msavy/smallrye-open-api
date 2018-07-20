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
package io.smallrye.openapi.runtime.scanner;

import io.smallrye.openapi.api.models.media.SchemaImpl;
import io.smallrye.openapi.runtime.scanner.indexwrapper.DataObjectDeque;
import io.smallrye.openapi.runtime.scanner.indexwrapper.FieldProcessor;
import io.smallrye.openapi.runtime.scanner.indexwrapper.WrappedIndexView;
import io.smallrye.openapi.runtime.util.SchemaFactory;
import io.smallrye.openapi.runtime.util.TypeUtil;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ArrayType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.PrimitiveType;
import org.jboss.jandex.Type;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Explores the class graph from the provided root, creating an OpenAPI {@link Schema}
 * from the entities encountered.
 *<p>
 * A depth first search is performed, with the following precedence (high to low):
 * <ol>
 *   <li>Explicitly provided attributes/overrides on <tt>@Schema</tt> annotated elements.
 *       Note that some attributes have special behaviours: for example, <tt>ref</tt> is mutually
 *       exclusive, and <tt>implementation</tt> replaces the implementation entirely.</li>
 *   <li>Unannotated fields unless property <tt>openapi.infer-unannotated-types</tt> set false</li>
 *   <li>Inferred attributes, such as name, type, format, etc.</li>
 * </ol>
 *
 * <p>
 * Well-known types, such as Collection, Map, Date, etc, are handled in a custom manner.
 * Jandex-indexed objects from the user's deployment are traversed until a terminal type is
 * met (such as a primitive, boxed primitive, date, etc), or an entity is encountered that is not
 * well-known or is not in the Jandex {@link IndexView}.
 *
 * <em>Current Limitations:</em>
 * If a type is not available in the provided IndexView then it is not accessible. Excepting
 * well-known types, this means non-deployment objects may not be scanned.
 * <p>
 * Future work could consider making the user's deployment classes available to this classloader,
 * with additional code to traverse non-Jandex types reachable from this classloader. But, this is
 * troublesome for performance, security and initialisation reasons -- particular caution would
 * be needed to avoid accidental initialisation of classes that may have externally visible side-effects.
 *
 * @see org.eclipse.microprofile.openapi.annotations.media.Schema Schema Annotation
 * @see Schema Schema Object
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class OpenApiDataObjectScanner {

    //    private static final Logger LOG = Logger.getLogger(OpenApiDataObjectScanner.class);
    // Object
    public static final Type OBJECT_TYPE = Type.create(DotName.createSimple(java.lang.Object.class.getName()), Type.Kind.CLASS);
    // Collection (list-type things)
    public static final DotName COLLECTION_INTERFACE_NAME = DotName.createSimple(Collection.class.getName());
    public static final Type COLLECTION_TYPE = Type.create(COLLECTION_INTERFACE_NAME, Type.Kind.CLASS);
    // Map
    public static final DotName MAP_INTERFACE_NAME = DotName.createSimple(Map.class.getName());
    public static final Type MAP_TYPE = Type.create(MAP_INTERFACE_NAME, Type.Kind.CLASS);
    // Enum
    public static final DotName ENUM_INTERFACE_NAME = DotName.createSimple(Enum.class.getName());
    public static final Type ENUM_TYPE = Type.create(ENUM_INTERFACE_NAME, Type.Kind.CLASS);
    // String type
    public static final Type STRING_TYPE = Type.create(DotName.createSimple(String.class.getName()), Type.Kind.CLASS);
    // Array type
    public static final Type ARRAY_TYPE_OBJECT = ArrayType.create(DotName.createSimple("[Ljava.lang.Object;"), Type.Kind.ARRAY);

    private Schema rootSchema;
    private final Type rootClassType;
    private final ClassInfo rootClassInfo;
    private final TypeUtil.TypeWithFormat rootClassTypeFormat;

    private final WrappedIndexView index;
    private final DataObjectDeque path;
    private final IgnoreResolver ignoreResolver;

    /**
     * Constructor for data object scanner.
     * <p>
     * Call {@link #process()} to build and return the {@link Schema}.
     *
     * @param _index    index of types to scan
     * @param classType root to begin scan
     */
    public OpenApiDataObjectScanner(IndexView _index, Type classType) {
        this.index = new WrappedIndexView(_index);
        this.path = new DataObjectDeque(index);
        this.ignoreResolver = new IgnoreResolver(index);
        this.rootClassType = classType;
        this.rootClassTypeFormat = TypeUtil.getTypeFormat(classType);
        this.rootSchema = new SchemaImpl();
        this.rootClassInfo = initialType(classType);
    }

    // Is Map, Collection, etc.
    private boolean isSpecialType(Type type) { // FIXME
        return isA(type, COLLECTION_TYPE) || isA(type, MAP_TYPE);
    }

    private ClassInfo initialType(Type type) {
        if (isA(type, COLLECTION_TYPE)) {
            return index.getClass(CollectionStandin.class);
        }

        if (isA(type, MAP_TYPE)) {
            return index.getClass(MapStandin.class);
        }

        return index.getClass(type);
    }

    /**
     * Build a Schema with ClassType as root.
     *
     * @param index index of types to scan
     * @param type  root to begin scan
     * @return the OAI schema
     */
    public static Schema process(IndexView index, Type type) {
        return new OpenApiDataObjectScanner(index, type).process();
    }

    /**
     * Build a Schema with PrimitiveType as root.
     *
     * @param primitive root to begin scan
     * @return the OAI schema
     */
    public static Schema process(PrimitiveType primitive) {
        TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(primitive);
        Schema primitiveSchema = new SchemaImpl();
        primitiveSchema.setType(typeFormat.getSchemaType());
        primitiveSchema.setFormat(typeFormat.getFormat().format());
        return primitiveSchema;
    }

    /**
     * Build a Schema with {@link ParameterizedType} as root.
     *
     * @param index index of types to scan
     * @param pType root to begin scan
     * @return the OAI schema
     */
    public static Schema process(IndexView index, ParameterizedType pType) {
        return new OpenApiDataObjectScanner(index, pType).process();
    }

    /**
     * Build the Schema
     *
     * @return the OAI schema
     */
    public Schema process() {
        //LOG.debugv("Starting processing with root: {0}", rootClassType.name());

        // If top level item is simple
        if (isTerminalType(rootClassType)) {
            SchemaImpl simpleSchema = new SchemaImpl();
            simpleSchema.setType(rootClassTypeFormat.getSchemaType());
            simpleSchema.setFormat(rootClassTypeFormat.getFormat().format());
            return simpleSchema;
        }

        // If top level item is not indexed
        if (rootClassInfo == null && path.isEmpty()) {
            // If there's something on the path stack then pre-scanning may have found something.
            return null;
        }

        // If top level is a special item, we need to skip search else it'll try to index fields
        // Embedded special items are handled ok.
        DataObjectDeque.PathEntry root = path.rootNode(rootClassType, rootClassInfo, rootSchema);

        // For certain special types (map, list, etc) we need to do some pre-processing.
        if (isSpecialType(rootClassType)) {
            resolveSpecial(root, rootClassType);
        } else {
            path.push(root);
        }

        dfs(path.peek());
        return rootSchema;
    }

    // Scan depth first.
    private void dfs(DataObjectDeque.PathEntry rootNode) {
        DataObjectDeque.PathEntry currentPathEntry = rootNode;

        while (!path.isEmpty()) {
            ClassInfo currentClass = currentPathEntry.getClazz();
            Schema currentSchema = currentPathEntry.getSchema();
            Type currentType = currentPathEntry.getClazzType();

            // First, handle class annotations.
            //currentSchema = readKlass(currentClass, currentSchema);
            // TODO is this necessary any more?
            currentPathEntry.setSchema(readKlass(currentClass, currentSchema));

            //LOG.debugv("Getting all fields for: {0} in class: {1}", currentType, currentClass);

            // Get all fields *including* inherited.
            Map<FieldInfo, TypeResolver> allFields = TypeResolver.getAllFields(index, currentType, currentClass);

            // Handle fields
            for (Map.Entry<FieldInfo, TypeResolver> entry : allFields.entrySet()) {
                FieldInfo field = entry.getKey();
                TypeResolver resolver = entry.getValue();
                // Ignore static fields and fields annotated with ignore.
                if (!Modifier.isStatic(field.flags()) && !ignoreResolver.isIgnore(field, currentPathEntry)) {
                    //LOG.tracev("Iterating field {0}", field);
//                    processField(field,
//                            resolver,
//                            currentSchema,
//                            currentPathEntry);
                    FieldProcessor.process(index, path, resolver, currentPathEntry, field);
                }
            }

            currentPathEntry = path.pop();
            // Handle methods
            // TODO put it here!
        }
    }

    private Schema readKlass(ClassInfo currentClass,
                             Schema currentSchema) {
        AnnotationInstance annotation = TypeUtil.getSchemaAnnotation(currentClass);
        if (annotation != null) {
            // Because of implementation= field, *may* return a new schema rather than modify.
            return SchemaFactory.readSchema(index, currentSchema, annotation, Collections.emptyMap());
        }
        return currentSchema;
    }

    private void resolveSpecial(DataObjectDeque.PathEntry root, Type type) {
        Map<FieldInfo, TypeResolver> fieldResolution = TypeResolver.getAllFields(index, type, rootClassInfo);
        rootSchema = preProcessSpecial(type, fieldResolution.values().iterator().next(), rootSchema, root);
    }

    private Schema preProcessSpecial(Type type, TypeResolver typeResolver, Schema parentSchema, DataObjectDeque.PathEntry currentPathEntry) {
        Schema fieldSchema = new SchemaImpl();
        AnnotationInstance schemaAnno = TypeUtil.getSchemaAnnotation(type);

        if (schemaAnno != null) {
            // 1. Handle field annotated with @Schema.
            //return readSchemaAnnotatedField(null, schemaAnno, type.name().toString(), type, typeResolver, parentSchema, fieldSchema, currentPathEntry);

            //return FieldProcessor.process(index, path, typeResolver, currentPathEntry, type);


        } else {
            // 2. Handle unannotated field and just do simple inference.
            //readUnannotatedField(null, typeResolver, type, fieldSchema, currentPathEntry);




            // Unannotated won't result in substitution, so just return field schema.
            return fieldSchema;
        }

        return fieldSchema;
    }
//
//    private Schema _processField(FieldInfo fieldInfo, TypeResolver typeResolver, Schema parentSchema, DataObjectDeque.PathEntry currentPathEntry) {
//        Schema fieldSchema = new SchemaImpl();
//        parentSchema.addProperty(fieldInfo.name(), fieldSchema);
//
//        AnnotationInstance schemaAnno = TypeUtil.getSchemaAnnotation(fieldInfo);
//
//        if (schemaAnno != null) {
//            // 1. Handle field annotated with @Schema.
//            return readSchemaAnnotatedField(
//                    fieldInfo,
//                    schemaAnno,
//                    fieldInfo.name(),
//                    fieldInfo.type(),
//                    typeResolver,
//                    parentSchema,
//                    fieldSchema,
//                    currentPathEntry);
//        } else {
//            // 2. Handle unannotated field and just do simple inference.
//            readUnannotatedField(fieldInfo, typeResolver, fieldInfo.type(), fieldSchema, currentPathEntry);
//            // Unannotated won't result in substitution, so just return field schema.
//            return fieldSchema;
//        }
//    }

//    private Schema readSchemaAnnotatedField(AnnotationTarget fieldInfo,
//                                            AnnotationInstance annotation,
//                                            String name,
//                                            Type type,
//                                            TypeResolver typeResolver,
//                                            Schema parent,
//                                            Schema schema,
//                                            DataObjectDeque.PathEntry pathEntry) {
//        if (annotation == null) {
//            return parent;
//        }
//
//        //LOG.debugv("Processing @Schema annotation {0} on a field {1}", annotation, name);
//
//        // Schemas can be hidden. Skip if that's the case.
//        Boolean isHidden = JandexUtil.booleanValue(annotation, OpenApiConstants.PROP_HIDDEN);
//        if (isHidden != null && isHidden == Boolean.TRUE) {
//            return parent;
//        }
//
//        // If "required" attribute is on field. It should be applied to the *parent* schema.
//        // Required is false by default.
//        if (JandexUtil.booleanValueWithDefault(annotation, OpenApiConstants.PROP_REQUIRED)) {
//            parent.addRequired(name);
//        }
//
//        // Type could be replaced (e.g. generics).
//        Type postProcessedField = processType(fieldInfo, type, typeResolver, schema, pathEntry);
//
//        // TypeFormat pair contains mappings for Java <-> OAS types and formats.
//        TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(postProcessedField);
//
//        // Provide inferred type and format if relevant.
//        Map<String, Object> overrides = new HashMap<>();
//        overrides.put(OpenApiConstants.PROP_TYPE, typeFormat.getSchemaType());
//        overrides.put(OpenApiConstants.PROP_FORMAT, typeFormat.getFormat().format());
//        return SchemaFactory.readSchema(index, schema, annotation, overrides);
//    }

//    private Type processType(AnnotationTarget annotationTarget, Type fieldType, TypeResolver typeResolver, Schema schema, DataObjectDeque.PathEntry pathEntry) {
//        // If it's a terminal type.
//        if (isTerminalType(fieldType)) {
//            return fieldType;
//        }
//
//        if (fieldType.kind() == Type.Kind.WILDCARD_TYPE) {
//            fieldType = TypeUtil.resolveWildcard(fieldType.asWildcardType());
//        }
//
//        if (fieldType.kind() == Type.Kind.ARRAY) {
//            //LOG.debugv("Processing an array {0}", fieldType);
//            ArrayType arrayType = fieldType.asArrayType();
//
//            // TODO handle multi-dimensional arrays.
//
//            // Array-type schema
//            SchemaImpl arrSchema = new SchemaImpl();
//            schema.type(Schema.SchemaType.ARRAY);
//            schema.items(arrSchema);
//
//            // Only use component (excludes the special name formatting for arrays).
//            TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(arrayType.component());
//            arrSchema.setType(typeFormat.getSchemaType());
//            arrSchema.setFormat(typeFormat.getFormat().format());
//
//            // If it's not a terminal type, then push for later inspection.
//            if (!isTerminalType(arrayType.component()) && index.containsClass(fieldType)) {
//                ClassInfo klazz = index.getClass(fieldType);
//                path.pushPathPair(annotationTarget, pathEntry, fieldType, klazz, arrSchema);
//            }
//            return arrayType;
//        }
//
//        if (isA(fieldType, ENUM_TYPE) && index.containsClass(fieldType)) {
//            //LOG.debugv("Processing an enum {0}", fieldType);
//            ClassInfo enumKlazz = index.getClass(fieldType);
//
//            for (FieldInfo enumField : enumKlazz.fields()) {
//                // Ignore the hidden enum array as it's not accessible. Add fields that look like enums (of type enumKlazz)
//                // NB: Eclipse compiler and OpenJDK compiler have different names for this field.
//                if (!enumField.name().endsWith("$VALUES") && TypeUtil.getName(enumField.type()).equals(enumKlazz.name())) {
//                    // Enum's value fields.
//                    schema.addEnumeration(enumField.name());
//                }
//            }
//            return STRING_TYPE;
//        }
//
//        if (fieldType.kind() == Type.Kind.PARAMETERIZED_TYPE) {
//            // Parameterised type (e.g. Foo<A, B>)
//            return readParamType(annotationTarget, pathEntry, schema, fieldType.asParameterizedType(), typeResolver);
//        }
//
//        if (fieldType.kind() == Type.Kind.TYPE_VARIABLE ||
//                fieldType.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE) {
//            // Resolve type variable to real variable.
//            return resolveTypeVariable(annotationTarget, typeResolver, schema, pathEntry, fieldType);
//        }
//
//        // Raw Collection
//        if (isA(fieldType, COLLECTION_TYPE)) {
//            return ARRAY_TYPE_OBJECT;
//        }
//
//        // Raw Map
//        if (isA(fieldType, MAP_TYPE)) {
//            return OBJECT_TYPE;
//        }
//
//        // Simple case: bare class or primitive type.
//        if (index.containsClass(fieldType)) {
//            path.pushField(annotationTarget, pathEntry, fieldType, schema);
//        } else {
//            // If the type is not in Jandex then we don't have easy access to it.
//            // Future work could consider separate code to traverse classes reachable from this classloader.
////            LOG.debugv("Encountered type not in Jandex index that is not well-known type. " +
////                    "Will not traverse it: {0}", fieldType);
//        }
//
//        return fieldType;
//    }

//    private Type resolveTypeVariable(AnnotationTarget fieldInfo, TypeResolver typeResolver, Schema schema, DataObjectDeque.PathEntry pathEntry, Type fieldType) {
//        // Type variable (e.g. A in Foo<A>)
//        Type resolvedType = typeResolver.getResolvedType(fieldType);
//
//        //LOG.debugv("Resolved type {0} -> {1}", fieldType, resolvedType);
//        if (isTerminalType(resolvedType) || index.getClass(resolvedType) == null) {
//            //LOG.tracev("Is a terminal type {0}", resolvedType);
//            TypeUtil.TypeWithFormat replacement = TypeUtil.getTypeFormat(resolvedType);
//            schema.setType(replacement.getSchemaType());
//            schema.setFormat(replacement.getFormat().format());
//        } else {
//            //LOG.debugv("Attempting to do TYPE_VARIABLE substitution: {0} -> {1}", fieldType, resolvedType);
//
//            if (index.containsClass(resolvedType)) {
//                // Look up the resolved type.
//                ClassInfo klazz = index.getClass(resolvedType);
//                DataObjectDeque.PathEntry entry = path.leafNode(pathEntry, fieldInfo, klazz, resolvedType, schema);
//                path.push(entry);
//            } else {
//                //LOG.debugv("Class for type {0} not available", resolvedType);
//            }
//        }
//        return resolvedType;
//    }

//    private void readUnannotatedField(AnnotationTarget field,
//                                      TypeResolver typeResolver,
//                                      Type type,
//                                      Schema schema,
//                                      DataObjectDeque.PathEntry pathEntry) {
//
//        if (!shouldInferUnannotatedFields()) {
//            return;
//        }
//
//        //LOG.debugv("Processing unannotated field {0}", type);
//
//        Type processedType = processType(field, type, typeResolver, schema, pathEntry);
//
//        TypeUtil.TypeWithFormat typeFormat = TypeUtil.getTypeFormat(processedType);
//        schema.setType(typeFormat.getSchemaType());
//
//        if (typeFormat.getFormat().hasFormat()) {
//            schema.setFormat(typeFormat.getFormat().format());
//        }
//    }

//    private Type readParamType(AnnotationTarget fieldInfo,
//                               DataObjectDeque.PathEntry pathEntry,
//                               Schema schema,
//                               ParameterizedType pType,
//                               TypeResolver typeResolver) {
//        //LOG.debugv("Processing parameterized type {0}", pType);
//
//        // If it's a collection, we should treat it as an array.
//        if (isA(pType, COLLECTION_TYPE)) { // TODO maybe also Iterable?
//            //LOG.debugv("Processing Java Collection. Will treat as an array.");
//            SchemaImpl arraySchema = new SchemaImpl();
//            schema.type(Schema.SchemaType.ARRAY);
//            schema.items(arraySchema);
//
//            // Should only have one arg for collection.
//            Type arg = pType.arguments().get(0);
//
//            if (isTerminalType(arg)) {
//                TypeUtil.TypeWithFormat terminalType = TypeUtil.getTypeFormat(arg);
//                arraySchema.type(terminalType.getSchemaType());
//                arraySchema.format(terminalType.getFormat().format());
//            } else if (arg.kind() == Type.Kind.TYPE_VARIABLE ||
//                    arg.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE ||
//                    arg.kind() == Type.Kind.WILDCARD_TYPE) {
//                Type resolved = resolveTypeVariable(fieldInfo, typeResolver, arraySchema, pathEntry, arg);
//                if (index.containsClass(resolved)) {
//                    arraySchema.type(Schema.SchemaType.OBJECT);
//                }
//            } else if (index.containsClass(arg)) {
//                arraySchema.type(Schema.SchemaType.OBJECT);
//                path.pushField(fieldInfo, pathEntry, arg, arraySchema);
//            }
//            return ARRAY_TYPE_OBJECT; // Representing collection as JSON array
//        } else if (isA(pType, MAP_TYPE)) {
//            //LOG.debugv("Processing Map. Will treat as an object.");
//            schema.type(Schema.SchemaType.OBJECT);
//
//            if (pType.arguments().size() == 2) {
//                Type valueType = pType.arguments().get(1);
//                SchemaImpl propsSchema = new SchemaImpl();
//                if (isTerminalType(valueType)) {
//                    TypeUtil.TypeWithFormat tf = TypeUtil.getTypeFormat(valueType);
//                    propsSchema.setType(tf.getSchemaType());
//                    propsSchema.setFormat(tf.getFormat().format());
//                } else if (valueType.kind() == Type.Kind.TYPE_VARIABLE ||
//                        valueType.kind() == Type.Kind.UNRESOLVED_TYPE_VARIABLE ||
//                        valueType.kind() == Type.Kind.WILDCARD_TYPE) {
//                    Type resolved = resolveTypeVariable(fieldInfo, typeResolver, propsSchema, pathEntry, valueType);
//                    if (index.containsClass(resolved)) {
//                        propsSchema.type(Schema.SchemaType.OBJECT);
//                    }
//                } else if (index.containsClass(valueType)) {
//                    propsSchema.type(Schema.SchemaType.OBJECT);
//                    path.pushField(fieldInfo, pathEntry, valueType, propsSchema);
//                }
//                schema.additionalProperties(propsSchema);
//            }
//            return OBJECT_TYPE;
//        } else {
//            // This attempts to allow us to resolve the types generically.
//            ClassInfo klazz = index.getClass(pType);
//            // Build mapping of class's type variables (e.g. A, B) to resolved variables
//            // Resolved variables could be any type (e.g. String, another param type, etc)
//            DataObjectDeque.PathEntry pair = path.leafNode(pathEntry, fieldInfo, klazz, pType, schema);
//            path.push(pair);
//            return pType;
//        }
//    }

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

    private boolean shouldInferUnannotatedFields() {
        String infer = System.getProperties().getProperty("openapi.infer-unannotated-types", "true");
        return Boolean.parseBoolean(infer);
    }

}
