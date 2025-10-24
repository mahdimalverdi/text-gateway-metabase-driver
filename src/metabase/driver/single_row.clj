(ns metabase.driver.single-row
  "A minimal Metabase driver that echoes the query text back in a single row."
  (:require [clojure.string :as str]
            [metabase.driver :as driver]))

(def ^:private column-metadata
  [{:name "result"
    :display_name "Result"
    :base_type :type/Text
    :effective_type :type/Text
    :database_type "text"
    :description "Echo of the submitted native query text."
    :field_ref [:field-literal "result" {:base-type :type/Text}]
    :visibility_type :normal}])

(defmethod driver/can-connect? :single-row
  [_ _details]
  true)

(defmethod driver/execute-reducible-query :single-row
  [_ query _context respond]
  ;; Echo the submitted native query text back to Metabase.
  (let [query-text (some-> query :native :query str/trim)
        result (if (seq query-text)
                 query-text
                 "No query text provided.")]
    (respond {:columns column-metadata}
             [[result]])))

(driver/register! :single-row)
