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
package test.io.smallrye.openapi.runtime.scanner.entities;

import java.util.List;

import org.eclipse.microprofile.openapi.apps.airlines.model.CreditCard;
import org.eclipse.microprofile.openapi.apps.airlines.model.Flight;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class SpecialCaseTestContainer {

    // Collection with concrete generic type.
    List<String> listOfString;

    // List of indexed object. NB: Do we remember to read this?
    List<CreditCard> ccList;

    // Wildcard with super bound
    List<? super Flight> listSuperFlight;

    // Wildcard with extends bound
    List<? extends Foo> listExtendsFoo;

    // Wildcard with no bound
    List<?> listOfAnything;
}
