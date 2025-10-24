(ns metabase.driver.http-echo
  "A Metabase driver that sends the submitted query text to an HTTP API and returns the JSON response."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [metabase.driver :as driver]))

(def ^:private default-endpoint "http://localhost:8080/api")
(def ^:private query-param-name "q")

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
    (or (map? v) (sequential? v)) (json/generate-string v)
    (nil? v) "null"
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
        {[] value})))

(defn- normalize-body [body]
  (->> (flatten-json body)
       (map (fn [[path value]]
              [(path->col-name path) value]))
       (into {})))

(defn- ->columns-and-row [body]
  (let [normalized (normalize-body body)
        entries (->> normalized
                     seq
                     (sort-by (comp key->str key)))
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
    {:columns columns
     :cols columns
     :row row}))

(defn- endpoint-from-query [query]
  (or (some-> query :database_details :endpoint)
      (some-> query :database :details :endpoint)
      (-> query :context :database :details :endpoint)))

(defn- endpoint-url [query]
  (or (endpoint-from-query query)
      (System/getenv "HTTP_ECHO_API_ENDPOINT")
      default-endpoint))

(defmethod driver/can-connect? :http-echo
  [_ details]
  ;; Optionally verify that an endpoint has been provided.
  (boolean (or (:endpoint details)
               (System/getenv "HTTP_ECHO_API_ENDPOINT")
               default-endpoint)))

(defmethod driver/execute-reducible-query :http-echo
  [_ query _context respond]
  ;; Submit the incoming query text to the configured HTTP API and surface the JSON response.
  (let [query-text (some-> query :native :query str/trim)
        endpoint (endpoint-url query)]
    (if-not (seq query-text)
      (respond {:columns [{:name "error"
                           :display_name "Error"
                           :base_type :type/Text
                           :effective_type :type/Text
                           :semantic_type :type/Text
                           :database_type "text"
                           :description "Error message from the driver."
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
                                :description "Error message from the driver."
                                :field_ref [:field-literal "error" {:base-type :type/Text}]
                                :visibility_type :normal
                                :source :native
                                :remapped_from nil}]}
               [["No query text provided."]])
      (try
        (let [{:keys [status body]} (http/get endpoint {:query-params {query-param-name query-text}
                                                        :accept :json
                                                        :as :json
                                                        :throw-exceptions false})]
          (if (<= 200 status 299)
            (let [{:keys [columns row]} (->columns-and-row body)
                  metadata {:columns columns
                            :cols columns}]
              (respond metadata
                       [row]))
            (respond {:columns [{:name "error"
                                 :display_name "Error"
                                 :base_type :type/Text
                                 :effective_type :type/Text
                                 :semantic_type :type/Text
                                 :database_type "text"
                                 :description "HTTP error returned while calling the external API."
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
                                      :description "HTTP error returned while calling the external API."
                                      :field_ref [:field-literal "error" {:base-type :type/Text}]
                                      :visibility_type :normal
                                      :source :native
                                      :remapped_from nil}]}
                     [[(format "HTTP %s calling %s" status endpoint)]])))
        (catch Exception e
          (respond {:columns [{:name "error"
                               :display_name "Error"
                               :base_type :type/Text
                               :effective_type :type/Text
                               :semantic_type :type/Text
                               :database_type "text"
                               :description "Exception thrown while calling the external API."
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
                                    :description "Exception thrown while calling the external API."
                                    :field_ref [:field-literal "error" {:base-type :type/Text}]
                                    :visibility_type :normal
                                    :source :native
                                    :remapped_from nil}]}
                 [[(.getMessage e)]]))))))

(driver/register! :http-echo)
