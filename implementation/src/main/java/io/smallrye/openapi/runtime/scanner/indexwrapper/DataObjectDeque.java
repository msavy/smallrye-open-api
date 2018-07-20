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

import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.Type;

import javax.validation.constraints.NotNull;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class DataObjectDeque {

    private final Deque<PathEntry> path = new ArrayDeque<>();
    private final WrappedIndexView index;

    public DataObjectDeque(WrappedIndexView index) {
        this.index = index;
    }

    public int size() {
        return path.size();
    }

    public boolean isEmpty() {
        return path.isEmpty();
    }

    public PathEntry peek() {
        return path.peek();
    }

    public void push(PathEntry entry) {
        path.push(entry);
    }

    public PathEntry pop() {
        return path.pop();
    }

    public void pushField(AnnotationTarget annotationTarget,
                                 PathEntry parentPathEntry,
                                 Type type,
                                 Schema schema) {
        ClassInfo klazzInfo = index.getClass(type);
        pushPathPair(annotationTarget, parentPathEntry, type, klazzInfo, schema);
    }

    public void pushPathPair(AnnotationTarget annotationTarget,
                              @NotNull PathEntry parentPathEntry,
                              @NotNull Type type,
                              @NotNull ClassInfo klazzInfo,
                              @NotNull Schema schema) {
        PathEntry entry = leafNode(parentPathEntry, annotationTarget, klazzInfo, type, schema);
        if (parentPathEntry.hasParent(entry)) {
            // Cycle detected, don't push path.
            //LOG.debugv("Possible cycle was detected at: {0}. Will not search further.", klazzInfo);
            //LOG.tracev("Path: {0}", entry.toStringWithGraph());
            if (schema.getDescription() == null) {
                schema.description("Cyclic reference to " + klazzInfo.name());
            }
        } else {
            // Push path to be inspected later.
            //LOG.debugv("Adding child node to path: {0}", klazzInfo);
            path.push(entry);
        }
    }

    public PathEntry rootNode(Type classType, ClassInfo classInfo, Schema rootSchema) {
        return new PathEntry(null, null, classInfo, classType, rootSchema);
    }

    public PathEntry leafNode(PathEntry parentNode,
                                     AnnotationTarget annotationTarget,
                                     ClassInfo classInfo,
                                     Type classType,
                                     Schema rootSchema) {
        return new PathEntry(parentNode, annotationTarget, classInfo, classType, rootSchema);
    }

    /**
     * Needed for non-recursive DFS to keep schema and class together.
     **/
    public static final class PathEntry {
        private final PathEntry enclosing;
        private final Type clazzType;
        private final ClassInfo clazz;
        private final Schema schema;
        private final AnnotationTarget annotationTarget;

        PathEntry(PathEntry enclosing,
                  AnnotationTarget annotationTarget,
                  ClassInfo clazz,
                  @NotNull Type clazzType,
                  @NotNull Schema schema) {
            this.enclosing = enclosing;
            this.annotationTarget = annotationTarget;
            this.clazz = clazz;
            this.clazzType = clazzType;
            this.schema = schema;
        }

        public boolean hasParent(PathEntry candidate) {
            PathEntry test = this;
            while (test != null) {
                if (candidate.equals(test)) {
                    return true;
                }
                test = test.enclosing;
            }
            return false;
        }

        public AnnotationTarget getAnnotationTarget() {
            return annotationTarget;
        }

        public PathEntry getEnclosing() {
            return enclosing;
        }

        public Type getClazzType() {
            return clazzType;
        }

        public ClassInfo getClazz() {
            return clazz;
        }

        public Schema getSchema() {
            return schema;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            PathEntry pair = (PathEntry) o;

            return clazz != null ? clazz.equals(pair.clazz) : pair.clazz == null;
        }

        @Override
        public int hashCode() {
            return clazz != null ? clazz.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "Pair{" +
                    "clazz=" + clazz +
                    ", schema=" + schema +
                    '}';
        }

        String toStringWithGraph() {
            return "Pair{" +
                    "clazz=" + clazz +
                    ", schema=" + schema +
                    ", parent=" + (enclosing != null ? enclosing.toStringWithGraph() : "<root>") + "}";
        }

    }
}
