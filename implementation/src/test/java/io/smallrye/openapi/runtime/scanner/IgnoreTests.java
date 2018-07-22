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

import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Type;
import org.json.JSONException;
import org.junit.Test;
import test.io.smallrye.openapi.runtime.scanner.entities.IgnoreTestContainer;

import java.io.IOException;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class IgnoreTests extends OpenApiDataObjectScannerTestBase {

    @Test
    public void testIgnore_jsonIgnorePropertiesOnClass() throws IOException, JSONException {
        String name = IgnoreTestContainer.class.getName();
        Type type = getFieldFromKlazz(name, "jipOnClassTest").type();
        OpenApiDataObjectScanner scanner = new OpenApiDataObjectScanner(index, type);

        Schema result = scanner.process();

        printToConsole(name, result);
        assertJsonEquals(name, "ignore.jsonIgnorePropertiesOnClass.expected.json", result);
    }

    @Test
    public void test() throws IOException {
        DotName kitchenSink = DotName.createSimple(IgnoreTestContainer.class.getName());
        OpenApiDataObjectScanner scanner = new OpenApiDataObjectScanner(index,
                ClassType.create(kitchenSink, Type.Kind.CLASS));
        Schema result = scanner.process();
        printToConsole("foo", result);
    }

    @Test
    public void testIgnore_jsonIgnorePropertiesOnField() throws IOException, JSONException {
        String name = IgnoreTestContainer.class.getName();
        FieldInfo fieldInfo = getFieldFromKlazz(name, "jipOnFieldTest");
        OpenApiDataObjectScanner scanner = new OpenApiDataObjectScanner(index, fieldInfo, fieldInfo.type());

        Schema result = scanner.process();

        printToConsole(name, result);
        assertJsonEquals(name, "ignore.jsonIgnorePropertiesOnField.expected.json", result);
    }
}
