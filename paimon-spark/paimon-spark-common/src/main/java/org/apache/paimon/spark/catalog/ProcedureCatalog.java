/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.spark.catalog;

import org.apache.paimon.spark.analysis.NoSuchProcedureException;
import org.apache.paimon.spark.procedure.Procedure;

import org.apache.spark.sql.connector.catalog.CatalogPlugin;
import org.apache.spark.sql.connector.catalog.Identifier;

/**
 * A {@link CatalogPlugin catalog} interface that loads stored procedures called via CALL
 * statements.
 */
public interface ProcedureCatalog extends CatalogPlugin {

    /**
     * Loads a {@link Procedure stored procedure} by {@link Identifier identifier}.
     *
     * @param identifier A stored procedure identifier.
     * @return The procedure's metadata of given identifier.
     * @throws NoSuchProcedureException Thrown, if there is no matching procedure stored.
     */
    Procedure loadProcedure(Identifier identifier) throws NoSuchProcedureException;
}
