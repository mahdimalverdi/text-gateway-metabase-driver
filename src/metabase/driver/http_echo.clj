(ns metabase.driver.http-echo
  "Metabase driver that proxies the incoming query text to a remote HTTP endpoint and turns the
  JSON response into a one-row result set."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.util.log :as log]))

(def ^:private query-param-name
  "Name of the query-string parameter we use when calling the remote API."
  "q")

(defn- key->str [k]
  (cond
    (keyword? k) (name k)
    (string? k) k
    :else (str k)))

(defn- display-name [k]
  (-> (key->str k)
      (str/replace #"[._\[\]-]+" " ")
      (str/capitalize)))

(defn- format-value [v]
  (cond
    (string? v) v
    (map? v) (json/generate-string v)
    (sequential? v) (json/generate-string v)
    (nil? v) "null"
    :else (str v)))

(defn- path-segment->string [segment]
  (cond
    (keyword? segment) (name segment)
    (string? segment) segment
    (integer? segment) (str "[" segment "]")
    :else (str segment)))

(defn- path->column-name [path]
  (if (seq path)
    (reduce
     (fn [acc segment]
       (let [seg (path-segment->string segment)]
         (cond
           (str/starts-with? seg "[") (str acc seg)
           (empty? acc) seg
           :else (str acc "." seg))))
     ""
     path)
    "result"))

(defn- flatten-json [value]
  (letfn [(walk [path v acc]
            (cond
              (map? v) (reduce-kv (fn [a k val] (walk (conj path k) val a)) acc v)
              (sequential? v) (reduce (fn [a [idx val]]
                                        (walk (conj path idx) val a))
                                      acc
                                      (map-indexed vector v))
              :else (assoc acc path v)))]
    (let [m (walk [] value {})]
      (if (seq m)
        m
        {[] value}))))

(defn- normalize-body [body]
  (->> (flatten-json body)
       (map (fn [[path value]]
              [(path->column-name path) value]))
       (into {})))

(defn- build-result [body]
  (let [normalized (normalize-body body)
        entries (->> normalized seq (sort-by (comp key->str key)))
        columns (mapv (fn [[column-name _]]
                        {:name column-name
                         :display_name (display-name column-name)
                         :base_type :type/Text
                         :effective_type :type/Text
                         :semantic_type :type/Text
                         :database_type "text"
                         :description (str "Value returned for " column-name)
                         :field_ref [:field-literal column-name {:base-type :type/Text}]
                         :visibility_type :normal
                         :source :native
                         :remapped_from nil})
                      entries)
        row (mapv (comp format-value val) entries)]
    {:metadata {:columns columns
                :cols columns}
     :row row}))

(defn- find-endpoint [value]
  (cond
    (map? value) (or (:endpoint value)
                     (get value "endpoint")
                     (some find-endpoint (vals value)))
    (sequential? value) (some find-endpoint value)
    :else nil))

(defn- endpoint-url [query]
  (find-endpoint query))

(def ^:private error-metadata
  {:columns [{:name "error"
              :display_name "Error"
              :base_type :type/Text
              :effective_type :type/Text
              :semantic_type :type/Text
              :database_type "text"
              :description "Error message from the HTTP Echo driver."
              :field_ref [:field-literal "error" {:base-type :type/Text}]
              :visibility_type :normal
              :source :native
              :remapped_from nil}]
   :cols [{:name "error"
           :display_name "Error"
           :base_type :type/Text
           :effective_type :type/Text
           :semantic_type :type/Text
           :database_type "text"
           :description "Error message from the HTTP Echo driver."
           :field_ref [:field-literal "error" {:base-type :type/Text}]
           :visibility_type :normal
           :source :native
           :remapped_from nil}]})

(defn- sortable-keys [value]
  (when (map? value)
    (-> value keys sort vec)))

(defn- respond-missing-endpoint [respond query]
  (let [database (when (map? (:database query)) (:database query))]
    (log/warn "HTTP Echo driver could not locate an API endpoint in the query payload"
              {:top-level-keys (sortable-keys query)
               :database-keys (sortable-keys database)
               :database-details (some-> database :details sortable-keys)
               :database-details-string (some-> database (get "details") sortable-keys)
               :query-keys (some-> query :native sortable-keys)}))
  (respond error-metadata
           [["No API endpoint configured for the HTTP Echo driver. Provide it in the connection details."]]))

(defn- respond-missing-query-text [respond]
  (respond error-metadata
           [["No query text provided."]]))

(defn- respond-http-error [respond status endpoint]
  (respond error-metadata
           [[(format "HTTP %s calling %s" status endpoint)]]))

(defn- respond-exception [respond e]
  (respond error-metadata
           [[(.getMessage e)]]))

(defn- execute-query! [endpoint query-text]
  (http/get endpoint {:query-params {query-param-name query-text}
                      :accept :json
                      :as :json
                      :throw-exceptions false}))

(defmethod driver/can-connect? :http-echo
  [_ details]
  (boolean (find-endpoint details)))

(defmethod driver/execute-reducible-query :http-echo
  [_ query _context respond]
  (let [query-text (some-> query :native :query str/trim)
        endpoint (endpoint-url query)]
    (cond
      (nil? endpoint)
      (respond-missing-endpoint respond query)

      (not (seq query-text))
      (respond-missing-query-text respond)

      :else
      (try
        (let [{:keys [status body]} (execute-query! endpoint query-text)]
          (if (<= 200 status 299)
            (let [{:keys [metadata row]} (build-result body)]
              (respond metadata [row]))
            (respond-http-error respond status endpoint)))
        (catch Exception e
          (respond-exception respond e))))))

(defonce ^:private register-driver-once
  (delay
    (driver/register! :http-echo)))

(defn init!
  "Register the HTTP Echo driver with Metabase. Invoked during plugin initialization."
  []
  (force register-driver-once))

(force register-driver-once)
