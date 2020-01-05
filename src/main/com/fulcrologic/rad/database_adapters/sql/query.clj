(ns com.fulcrologic.rad.database-adapters.sql.query
  "This namespace provides query builders for various concerns of
  a SQL database in a RAD application. The main concerns are:

  - Fetch queries (with joins) coming from resolvers
  - Building custom queries based of off RAD attributes
  - Persisting data based off submitted form deltas"
  (:require
    [com.fulcrologic.rad                                  :as rad]
    [com.fulcrologic.rad.attributes                       :as attr]
    [com.fulcrologic.rad.database-adapters.sql            :as rad.sql]
    [com.fulcrologic.rad.database-adapters.sql.result-set :as sql.rs]
    [com.fulcrologic.rad.database-adapters.sql.schema     :as sql.schema]
    [clojure.string                                       :as str]
    [edn-query-language.core                              :as eql]
    [next.jdbc.sql                                        :as jdbc.sql]
    [next.jdbc.sql.builder                                :as jdbc.builder]
    [taoensso.encore                                      :as enc]
    [taoensso.timbre                                      :as log]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom queries

(defn query
  "Wraps next.jbdc's query, but will return fully qualified keywords
  for any matching attributes found in `::rad/attributes` in the
  options map."
  [db stmt opts]
  (jdbc.sql/query (:datasource db)
    stmt
    (merge {:builder-fn sql.rs/as-qualified-maps
            :key-fn (let [idx (sql.schema/attrs->sql-col-index
                                (::rad/attributes opts))]
                      (fn [table column]
                        (get idx [table column]
                          (keyword table column))))}
      opts)))


(defn where-attributes
  "Generates a query using next.jdbc's builders, and executes it. Any
  columns in the result set will be efficiently namespaced according
  to the attributes in `::rad/attributes` option."
  [db table where opts]
  (let [where-clause (enc/map-keys sql.schema/attr->column-name where)]
    (query db (jdbc.builder/for-query table where-clause opts) opts)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity query


(defn query->plan
  "Given an EQL query, plans a sql query that would fetch the entities
  and their joins"
  [query {:keys [::rad/attributes
                 ::id-attribute]}]
  (let [{:keys [prop join]} (group-by :type (:children (eql/query->ast query)))
        table               (sql.schema/attr->table-name id-attribute)
        k->attr             (enc/keys-by ::attr/qualified-key attributes)
        k->column           (comp sql.schema/attr->column-name k->attr)
        node->column        (comp k->column :dispatch-key)
        node->table         (comp sql.schema/attr->table-name k->attr :dispatch-key)

        ->columns           (fn [{:keys [nodes pk-attr]}]
                              (-> (keep node->column nodes)
                                (conj (sql.schema/attr->column-name pk-attr))
                                (distinct)))]
    {::fields (concat
                ;; Entity fields
                [[table (->columns {:nodes prop :pk-attr id-attribute})]]
                ;; Join fields
                (for [node join]
                  (if-let [pk-attr (-> node
                                     :dispatch-key k->attr
                                     ::attr/target k->attr)]
                    [(node->table node)
                     (->columns {:nodes (:children node)
                                 :pk-attr pk-attr})]
                    (throw (ex-info "Invalid target for join"
                             {:key (:dispatch-key node)})))))
     ::from table
     ::joins (for [node join]
               [[(node->table node) (node->column node)]
                [table (sql.schema/attr->column-name id-attribute)]])
     :parser/entity-key  (::attr/qualified-key id-attribute)
     :parser/entity-keys (map :dispatch-key prop)
     :parser/nested-key  (:dispatch-key (first join))
     :parser/nested-keys (map :dispatch-key (:children (first join)))}))


(defn plan->sql
  "Given a query plan, return the sql statement that matches the plan"
  [{::keys [fields from joins]}]
  (str "SELECT " (str/join ", " (for [[table cols] fields
                                      col cols]
                                  (str table ".\"" col \")))
    " FROM " from
    (when (seq joins)
      (str/join ", "
        (for [[[ltable lcolumn] [rtable rcolumn]] joins]
          (str " LEFT JOIN " ltable " ON "
            ltable ".\"" lcolumn "\" = "
            rtable ".\"" rcolumn \"))))))


(defn parse-eql-result
  "Groups and nests all entities with their joins after executing the
  plan"
  [result {:parser/keys [entity-key entity-keys nested-key nested-keys]}]
  (->> result
    (group-by entity-key)
    (vals)
    (map (fn [group]
           (assoc (select-keys (first group) entity-keys)
             nested-key (->> group
                          (filter #(some % nested-keys))
                          (map #(select-keys % nested-keys))))))))


(defn eql-query [db q opts]
  (let [plan (query->plan q opts)
        sql  (plan->sql plan)
        result (query db [sql] opts)]
    (with-meta (parse-eql-result result plan)
      {::sql sql})))


(defn- id->query-value [id-attr v]
  (let [t (::attr/type id-attr)]
    (case t
      (:text :uuid) (str "'" v "'")
      v)))


(defn- column-names [attributes query]
  (let [desired-keys       (->> query
                             (eql/query->ast)
                             (:children)
                             (map :dispatch-key)
                             (set))
        desired-attributes (filterv
                             #(contains? desired-keys (::attr/qualified-key %))
                             attributes)]
    (mapv sql.schema/attr->column-name desired-attributes)))


(defn entity-query [{::rad.sql/keys [schema attributes id-attribute] :as env} input]
  (let [one? (not (sequential? input))]
    (enc/if-let [db               (get-in env [::rad.sql/databases schema])
                 id-key           (::attr/qualified-key id-attribute)
                 table            (sql.schema/attr->table-name id-attribute)
                 query*           (or
                                    (get env :com.wsscode.pathom.core/parent-query)
                                    (get env ::rad.sql/default-query))
                 to-v             (partial id->query-value id-attribute)
                 ids              (if one?
                                    [(to-v (get input id-key))]
                                    (into [] (keep #(some-> %
                                                      (get id-key)
                                                      to-v) input)))
                 sql              (str
                                    "SELECT " (str/join ", "
                                                (column-names attributes query*))
                                    " FROM " table
                                    " WHERE " (sql.schema/attr->column-name id-attribute)
                                    " IN (" (str/join "," ids) ")")]
      (do
        (log/info "Running" sql "on entities with " id-key ":" ids)
        (let [result (query db [sql] {::rad/attributes attributes})]
          (if one?
            (first result)
            result)))
      (log/info "Unable to complete query."))))