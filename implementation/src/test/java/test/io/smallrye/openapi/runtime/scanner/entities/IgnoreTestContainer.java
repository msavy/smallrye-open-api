package test.io.smallrye.openapi.runtime.scanner.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @author Marc Savy {@literal <marc@rhymewithgravy.com>}
 */
public class IgnoreTestContainer {
    // Should be ignored by virtue of @JsonIgnore
    @JsonIgnore
    String jsonIgnoreTest;

    // Should ignore @JsonIgnoreProperty nominated properties for this instance only.
    @JsonIgnoreProperties({"aLongProperty"})
    SimpleValues jipOnFieldTest;

    JsonIgnorePropertiesOnClassExample jipOnClassTest;
}
