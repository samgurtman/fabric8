/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.camel.tooling.util;

import java.io.File;

import org.junit.Test;

import static org.junit.Assert.*;

public class NewSchemaXmlTest extends RouteXmlTestSupport {

    @Test
    public void testParsesRecentNewSchemaFeaturesXmlFile() throws Exception {
        XmlModel m = assertRoutes(new File(getBaseDir(), "src/test/resources/newSchemaChanges.xml"), 1, null);
        ValidationHandler status = m.validate();
        //System.out.println("Validation errors: " + status.userMessage());
        assertTrue("Should not have any validation errors: " + status.userMessage(), !status.hasErrors());
    }

}
