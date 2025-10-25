(ns metabase.driver.http-echo
  "A Metabase driver that sends the submitted query text to an HTTP API (GET or POST)
   and returns the JSON response as a tabular result."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [metabase.driver :as driver]
            [metabase.util.log :as log]))

;; -------------------------------
;; Shared constants and utilities
;; -------------------------------

(def ^:private query-param-name "q")

(def ^:private error-column
  {:name "error"
   :display_name "Error"
   :base_type :type/Text
   :effective_type :type/Text
   :semantic_type :type/Text
   :database_type "text"
   :description "Error message from the driver."
   :field_ref [:field-literal "error" {:base-type :type/Text}]
   :visibility_type :normal
   :source :native
   :remapped_from nil})

(def ^:private error-metadata
  {:columns [error-column]
   :cols [error-column]})

(defn- respond-error [respond message]
  (respond error-metadata [[message]]))

(defn- key->str [k]
  (cond
    (keyword? k) (name k)
    (string? k) k
    :else (str k)))

(defn- display-name [k]
  (-> (key->str k)
      (str/replace #"[._\[\]-]+" " ")
      (str/capitalize)))

(defn- fmt-value [v]
  (cond
    (string? v) v
    (number? v) v
    (boolean? v) (str v)
    (or (map? v) (sequential? v)) (json/generate-string v)
    (nil? v) nil
    :else (str v)))

(defn- path->segment [segment]
  (cond
    (keyword? segment) (name segment)
    (string? segment) segment
    (int? segment) (str "[" segment "]")
    :else (str segment)))

(defn- path->col-name [path]
  (if (seq path)
    (reduce (fn [acc segment]
              (let [seg-str (path->segment segment)]
                (cond
                  (str/starts-with? seg-str "[") (str acc seg-str)
                  (empty? acc) seg-str
                  :else (str acc "." seg-str))))
            ""
            path)
    "result"))

(defn- flatten-json [value]
  (letfn [(step [path v acc]
            (cond
              (map? v) (reduce-kv (fn [a k val] (step (conj path k) val a)) acc v)
              (sequential? v) (reduce (fn [a [idx val]]
                                        (step (conj path idx) val a))
                                      acc
                                      (map-indexed vector v))
              :else (assoc acc path v)))]
    (let [result (step [] value {})]
      (if (seq result)
        result
        {[] value}))))

(defn- normalize-body [body]
  (->> (flatten-json body)
       (map (fn [[path value]]
              [(path->col-name path) value]))
       (into {})))

(defn- ->columns-and-row [body]
  (let [normalized (normalize-body body)
        entries (->> normalized seq (sort-by (comp key->str key)))
        columns (mapv (fn [[k _]]
                        (let [col-name (key->str k)]
                          {:name col-name
                           :display_name (display-name k)
                           :base_type :type/Text
                           :effective_type :type/Text
                           :semantic_type :type/Text
                           :database_type "text"
                           :description (str "Value returned for " col-name)
                           :field_ref [:field-literal col-name {:base-type :type/Text}]
                           :visibility_type :normal
                           :source :native
                           :remapped_from nil}))
                      entries)
        row (mapv (comp fmt-value val) entries)]
    {:columns columns :cols columns :row row}))

(defn- ->rows-and-columns [body]
  (if (sequential? body)
    (let [rows (map ->columns-and-row body)
          first-cols (:columns (first rows))
          all-rows (map :row rows)]
      {:columns first-cols
       :cols first-cols
       :rows all-rows})
    (let [{:keys [columns cols row]} (->columns-and-row body)]
      {:columns columns
       :cols cols
       :rows [row]})))

;; -------------------------------
;; Endpoint extraction and helpers
;; -------------------------------

(defn- endpoint-from-any [value]
  (cond
    (map? value) (or (:endpoint value)
                     (get value "endpoint")
                     (some endpoint-from-any (vals value)))
    (sequential? value) (some endpoint-from-any value)
    :else nil))

(defn- env-endpoint []
  (or (System/getenv "MB_HTTP_ECHO_ENDPOINT")
      (System/getProperty "mb.http.echo.endpoint")))

(defn- endpoint-url [query context]
  (let [candidates [query
                    (:info query)
                    (-> query :info :database)
                    (-> query :info :details)
                    context
                    (:database context)
                    (:details context)
                    (get context :details)
                    (get context "details")
                    (-> context :database :details)
                    (-> context :database (get "details"))
                    (get context :database)
                    (get context "database")
                    {:endpoint (env-endpoint)}]]
    (some endpoint-from-any candidates)))

(defn- sortable-keys [value]
  (when (map? value)
    (-> value keys sort vec)))

(defn- respond-missing-endpoint [respond query context]
  (let [database (when (map? (:database query)) (:database query))]
    (log/warn "HTTP Echo driver could not locate an API endpoint"
              {:query-top-level-keys (sortable-keys query)
               :query-database-keys (sortable-keys database)
               :query-database-details (some-> database :details sortable-keys)
               :query-database-details-string (some-> database (get "details") sortable-keys)
               :query-native-keys (some-> query :native sortable-keys)
               :context-keys (sortable-keys context)
               :context-database-keys (some-> context :database sortable-keys)
               :context-details-keys (some-> context :details sortable-keys)
               :context-db-details-keys (some-> context :database :details sortable-keys)}))
  (respond-error respond "No API endpoint configured for the HTTP Echo driver. Provide it in the connection details."))

;; -------------------------------
;; Metabase Driver Implementations
;; -------------------------------

(defmethod driver/connection-properties :http-echo
  [_]
  [{:name "endpoint"
    :display-name "API Endpoint"
    :placeholder "https://example.com/api"
    :required true
    :sensitive false
    :section "connection"
    :description "Base URL of the target HTTP API that will receive the query text."}
   {:name "method"
    :display-name "HTTP Method"
    :placeholder "GET"
    :required false
    :default "GET"
    :options ["GET" "POST"]
    :section "connection"
    :description "HTTP method used for the request. Defaults to GET."}])

(defmethod driver/can-connect? :http-echo
  [_ details]
  (boolean (endpoint-from-any details)))

(defmethod driver/execute-reducible-query :http-echo
  [_ query context respond]
  (let [query-text (some-> query :native :query str/trim)
        endpoint (endpoint-url query context)
        http-method (or (some-> context :database :details :method str/lower-case)
                        "get")]
    (cond
      (not endpoint)
      (respond-missing-endpoint respond query context)

      (not (seq query-text))
      (respond-error respond "No query text provided.")

      :else
      (try
        (let [request-opts {:accept :json
                            :as :json
                            :throw-exceptions false}
              response (case http-method
                         "post" (http/post endpoint (assoc request-opts :form-params {query-param-name query-text}))
                         (http/get  endpoint (assoc request-opts :query-params {query-param-name query-text})))
              {:keys [status body]} response]
          (if (<= 200 status 299)
            (let [{:keys [columns cols rows]} (->rows-and-columns body)
                  metadata {:columns columns :cols cols}]
              (respond metadata rows))
            (do
              (log/warn "HTTP Echo driver API call returned non-2xx status"
                        {:endpoint endpoint :status status})
              (respond-error respond (format "HTTP %s calling %s" status endpoint)))))
        (catch Exception e
          (log/error e "Exception in HTTP Echo driver")
          (respond-error respond (.getMessage e)))))))

;; -------------------------------
;; Driver registration
;; -------------------------------

(defonce ^:private register-driver-once
  (delay
    (try
      (driver/register! :http-echo)
      (catch IllegalArgumentException e
        (if (some-> e .getMessage (str/includes? "already registered"))
          (log/info "HTTP Echo driver already registered; continuing.")
          (throw e))))))

(defn init!
  []
  (force register-driver-once))

(force register-driver-once)
