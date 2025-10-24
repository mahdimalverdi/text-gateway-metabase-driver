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
      (str/replace #"[-_]+" " ")
      (str/capitalize)))

(defn- fmt-value [v]
  (cond
    (string? v) v
    (or (map? v) (sequential? v)) (json/generate-string v)
    (nil? v) "null"
    :else (str v)))

(defn- normalize-body [body]
  (cond
    (map? body) body
    (string? body) {:result body}
    (or (sequential? body) (nil? body)) {:result (fmt-value body)}
    :else {:result (str body)}))

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
                           :source :native}))
                      entries)
        row (mapv (comp fmt-value val) entries)]
    {:columns columns
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
                           :source :native}]}
               [["No query text provided."]])
      (try
        (let [{:keys [status body]} (http/get endpoint {:query-params {query-param-name query-text}
                                                        :accept :json
                                                        :as :json
                                                        :throw-exceptions false})]
          (if (<= 200 status 299)
            (let [{:keys [columns row]} (->columns-and-row body)]
              (respond {:columns columns}
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
                                 :source :native}]}
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
                               :source :native}]}
                   [[(.getMessage e)]]))))))

(driver/register! :http-echo)
