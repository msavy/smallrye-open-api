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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import io.smallrye.openapi.runtime.scanner.indexwrapper.DataObjectDeque;
import io.smallrye.openapi.runtime.util.TypeUtil;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class IgnoreResolver {

    private final Map<DotName, IgnoreAnnotationHandler> IGNORE_ANNOTATION_MAP = new LinkedHashMap<>();

    {
        IgnoreAnnotationHandler[] ignoreHandlers = {
                new JsonIgnorePropertiesHandler(),
                new JsonIgnoreHandler(),
                new JsonIgnoreTypeHandler()
        };

        for (IgnoreAnnotationHandler handler : ignoreHandlers) {
            IGNORE_ANNOTATION_MAP.put(handler.getName(), handler);
        }
    }

    private final IndexView index;

    public IgnoreResolver(IndexView index) {
        this.index = index;
    }

    public boolean isIgnore(AnnotationTarget annotationTarget, DataObjectDeque.PathEntry pathEntry) {
        for (IgnoreAnnotationHandler handler : IGNORE_ANNOTATION_MAP.values()) {
            boolean result = handler.shouldIgnore(annotationTarget, pathEntry);
            if (result) {
                return true;
            }
        }
        return false;
    }

    private final class JsonIgnorePropertiesHandler implements IgnoreAnnotationHandler {

        @Override
        public boolean shouldIgnore(AnnotationTarget target, DataObjectDeque.PathEntry parentPathEntry) {

            if (target.kind() == AnnotationTarget.Kind.FIELD) {
                // First look at declaring class for @JsonIgnoreProperties
                // Then look at enclosing type.
                FieldInfo field = target.asField();
                return declaringClassIgnore(field) || nestingFieldIgnore(parentPathEntry.getAnnotationInstance(), field.name());
            }
            return false;
        }

        // Declaring class ignore
        //
        //  @JsonIgnoreProperties("ignoreMe")
        //  class A {
        //    String ignoreMe;
        //  }
        private boolean declaringClassIgnore(FieldInfo field) {
            AnnotationInstance declaringClassJIP = TypeUtil.getAnnotation(field.declaringClass(), getName());
            return shouldIgnoreTarget(declaringClassJIP, field.name());
        }

        // Look for nested/enclosing type @JsonIgnoreProperties.
        //
        // class A {
        //   @JsonIgnoreProperties("ignoreMe")
        //   B foo;
        // }
        //
        // class B {
        //   String ignoreMe; // Ignored during scan via A.
        //   String doNotIgnoreMe;
        // }
        private boolean nestingFieldIgnore(AnnotationInstance nesting, String fieldName) {
            if (nesting == null) {
                return false;
            }
            AnnotationInstance nestedTypeJIP = TypeUtil.getAnnotation(nesting.target(), getName());
            return shouldIgnoreTarget(nestedTypeJIP, fieldName);
        }

        private boolean shouldIgnoreTarget(AnnotationInstance jipAnnotation, String targetName) {
            if (jipAnnotation == null) {
                return false;
            }

            String[] jipValues = jipAnnotation.value().asStringArray();

            if (Arrays.binarySearch(jipValues, targetName) >= 0) {
                System.out.println("Will ignore field " + targetName);
                return true;
            } else {
                System.out.println("Will keep field " + targetName);
                return false;
            }
        }

        @Override
        public DotName getName() {
            return DotName.createSimple(JsonIgnoreProperties.class.getName());
        }
    }

    private final class JsonIgnoreHandler implements IgnoreAnnotationHandler {

        @Override
        public boolean shouldIgnore(AnnotationTarget target, DataObjectDeque.PathEntry parentPathEntry) {
            AnnotationInstance annotationInstance = TypeUtil.getAnnotation(target, getName());
            if (annotationInstance != null) {
                return valueAsBooleanOrTrue(annotationInstance);
            }
            return false;
        }

        @Override
        public DotName getName() {
            return DotName.createSimple(JsonIgnore.class.getName());
        }
    }

    private final class JsonIgnoreTypeHandler implements IgnoreAnnotationHandler {
        private Set<DotName> ignoredTypes = new LinkedHashSet<>();

        @Override
        public boolean shouldIgnore(AnnotationTarget target, DataObjectDeque.PathEntry parentPathEntry) {
            DotName typeName = null;
            Collection<AnnotationInstance> annotations = Collections.emptySet();

            if (target.kind() == AnnotationTarget.Kind.CLASS) {
                typeName = target.asClass().name();
                annotations = target.asClass().classAnnotations();
            } else if (target.kind() == AnnotationTarget.Kind.FIELD) {
                typeName = target.asField().type().name();
                annotations = Optional.ofNullable(index.getClassByName(typeName))
                        .map(ClassInfo::classAnnotations)
                        .orElse(Collections.emptySet());
            } //todo other kinds needed? method?

            if (typeName != null && ignoredTypes.contains(typeName)) {
                System.out.println("We're ignoring the type " + typeName);
                return true;
            }

            AnnotationInstance annotationInstance = getAnnotation(annotations, getName());
            if (annotationInstance != null && valueAsBooleanOrTrue(annotationInstance)) {
                // Add the ignored field or class name
                System.out.println("Ignoring type " + typeName);
                ignoredTypes.add(typeName);
                return true;
            }
            return false;
        }

        private AnnotationInstance getAnnotation(Collection<AnnotationInstance> annotations, DotName name) {
            return annotations.stream()
                    .filter(annotation -> annotation.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public DotName getName() {
            return DotName.createSimple(JsonIgnoreType.class.getName());
        }
    }

    private boolean valueAsBooleanOrTrue(AnnotationInstance annotation) {
        return Optional.ofNullable(annotation.value())
                .map(AnnotationValue::asBoolean)
                .orElse(true);
    }

    private interface IgnoreAnnotationHandler {
        boolean shouldIgnore(AnnotationTarget target, DataObjectDeque.PathEntry parentPathEntry);

        DotName getName();
    }

}
