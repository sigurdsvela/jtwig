/**
 * Copyright 2012 Lyncode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lyncode.jtwig.functions.internal.string;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FormatTest {
    private Format underTest = new Format();

    @Test
    public void testExecute() throws Exception {
        assertEquals("I like foo and bar.", underTest.execute("I like %s and %s.", "foo", "bar"));
        assertEquals("I like it.", underTest.execute("I like it."));
    }
}
