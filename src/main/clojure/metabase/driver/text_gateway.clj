(ns metabase.driver.text-gateway
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.string :as str]
    [metabase.driver :as driver]
    [metabase.query-processor.interface :as qpi]
    [metabase.util.log :as log]))

;; Register driver keyword
(driver/register! :text-gateway)

(defn- trim-slash [^String s]
  (if (and s (.endsWith s "/")) (subs s 0 (dec (count s))) s))

(defmethod driver/supports? :text-gateway [_ feature]
  ;; We support native parameters in the editor; actual query is free-form text.
  (contains? #{:native-parameters} feature))

(defmethod driver/connection-properties :text-gateway [_] [])

(defmethod driver/can-connect? :text-gateway [_ {:keys [base_url api_key]}]
  (try
    (let [url (str (trim-slash base_url) "/health")
          resp (http/get url {:headers (cond-> {}
                                         api_key (assoc "Authorization" (str "Bearer " api_key)))
                              :throw-exceptions false
                              :as :string})]
      (<= 200 (:status resp) 299))
    (catch Exception e
      (log/error e "text-gateway connectivity failed")
      false)))

(defmethod driver/describe-database :text-gateway
  [_ _] {:name "text-gateway" :tables []})

;; Reducible query execution; Metabase calls this with the expanded native query string.
(defmethod driver/execute-reducible-query :text-gateway
  [driver {:keys [settings] :as _database} {{:keys [query]} :native :as mb-query} context respond! raise!]
  (try
    (let [base-url (:base_url settings)
          api-key  (:api_key settings)
          endpoint (str (trim-slash base-url) "/run")
          ;; The body our service expects: {"text":"<query string from editor>"}
          resp (http/post endpoint
                          {:headers (cond-> {"Content-Type" "application/json"}
                                      api-key (assoc "Authorization" (str "Bearer " api-key)))
                           :body (json/encode {:text (str (or query ""))})
                           :throw-exceptions true
                           :as :string})
          body (json/parse-string (:body resp) keyword)
          rows (vec (get body :rows []))
          cols (->> (if (seq rows) (keys (first rows)) [])
                    (map (fn [k] {:name (name k)
                                  :display_name (name k)
                                  :base_type :type/Text}))
                    vec)
          data {:columns cols
                :rows (mapv (fn [r] (mapv #(get r (keyword (:name %))) cols)) rows)}]
      (respond! data))
    (catch Exception e
      (raise! e))))
