/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class TestSkipAnonymousClassAndLambda {
    public void fun(String a) {
        if (a.length() > 5) {
            a += "long";
            return;
        }
        a =
                new InnerClass() {
                    @Override
                    public String getValue() {
                        return a + "test";
                    }
                }.getValue();
        java.util.function.Function<String, String> f =
                s -> {
                    s += "test";
                    return s;
                };
        a = f.apply(a);
        a += "short";
        return;
    }

    private abstract class InnerClass {
        public abstract String getValue();
    }
}
