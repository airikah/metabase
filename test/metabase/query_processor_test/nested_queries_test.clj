(ns metabase.query-processor-test.nested-queries-test
  "Tests for handling queries with nested expressions."
  (:require [clojure.string :as str]
            [expectations :refer [expect]]
            [honeysql.core :as hsql]
            [metabase
             [query-processor :as qp]
             [query-processor-test :refer :all]
             [util :as u]]
            [metabase.models
             [card :refer [Card]]
             [database :as database]
             [field :refer [Field]]
             [table :refer [Table]]]
            [metabase.test.data :as data]
            [metabase.test.data.datasets :as datasets]
            [toucan.db :as db]
            [toucan.util.test :as tt]))

(defn- rows+cols
  "Return the `:rows` and relevant parts of `:cols` from the RESULTS.
   (This is used to keep the output of various tests below focused and manageable.)"
  {:style/indent 0}
  [results]
  {:rows (rows results)
   :cols (for [col (get-in results [:data :cols])]
           {:name      (str/lower-case (:name col))
            :base_type (:base_type col)})})


;; make sure we can do a basic query with MBQL source-query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  {:rows [[1 "Red Medicine"                  4 10.0646 -165.374 3]
          [2 "Stout Burgers & Beers"        11 34.0996 -118.329 2]
          [3 "The Apple Pan"                11 34.0406 -118.428 2]
          [4 "Wurstküche"                   29 33.9997 -118.465 2]
          [5 "Brite Spot Family Restaurant" 20 34.0778 -118.261 2]]
   :cols [{:name "id",          :base_type :type/BigInteger}
          {:name "name",        :base_type :type/Text}
          {:name "category_id", :base_type :type/Integer}
          {:name "latitude",    :base_type :type/Float}
          {:name "longitude",   :base_type :type/Float}
          {:name "price",       :base_type :type/Integer}]}
  (rows+cols
    (qp/process-query
      {:database (data/id)
       :type     :query
       :query    {:source-query {:source-table (data/id :venues)
                                 :order-by     [:asc (data/id :venues :id)]
                                 :limit        10}
                  :limit        5}})))

(defn- venues-identifier
  "Return the identifier for the venues Table for the current DB.
   (Normally this is just `venues`, but some databases like Redshift do clever hacks
   like prefixing table names with a unique schema for each test run because we're not
   allowed to create new databases.)"
  ([]
   (let [{schema :schema, table-name :name} (db/select-one [Table :name :schema] :id (data/id :venues))]
     (name (hsql/qualify schema table-name))))
  ([column]
   (db/select-one-field :name Field :table_id (data/id :venues), :%lower.name (str/lower-case (name column)))))

;; make sure we can do a basic query with a SQL source-query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  {:rows [[1 -165.374  4 3 "Red Medicine"                 10.0646]
          [2 -118.329 11 2 "Stout Burgers & Beers"        34.0996]
          [3 -118.428 11 2 "The Apple Pan"                34.0406]
          [4 -118.465 29 2 "Wurstküche"                   33.9997]
          [5 -118.261 20 2 "Brite Spot Family Restaurant" 34.0778]]
   :cols [{:name "id",          :base_type :type/Integer}
          {:name "longitude",   :base_type :type/Float}
          {:name "category_id", :base_type :type/Integer}
          {:name "price",       :base_type :type/Integer}
          {:name "name",        :base_type :type/Text}
          {:name "latitude",    :base_type :type/Float}]}
  (rows+cols
    (qp/process-query
      {:database (data/id)
       :type     :query
       :query    {:source-query {:native (format "SELECT %s, %s, %s, %s, %s, %s FROM %s"
                                                 (venues-identifier :id)
                                                 (venues-identifier :longitude)
                                                 (venues-identifier :category_id)
                                                 (venues-identifier :price)
                                                 (venues-identifier :name)
                                                 (venues-identifier :latitude)
                                                 (venues-identifier))}
                  :order-by     [:asc [:field-literal (keyword (data/format-name :id)) :type/Integer]]
                  :limit        5}})))

(def ^:private ^:const breakout-results
  {:rows [[1 22]
          [2 59]
          [3 13]
          [4  6]]
   :cols [{:name "price", :base_type :type/Integer}
          {:name "count", :base_type :type/Integer}]})

;; make sure we can do a query with breakout and aggregation using an MBQL source query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  breakout-results
  (rows+cols
    (format-rows-by [int int]
      (qp/process-query
        {:database (data/id)
         :type     :query
         :query    {:source-query {:source-table (data/id :venues)}
                    :aggregation  [:count]
                    :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}}))))

;; make sure we can do a query with breakout and aggregation using a SQL source query
(datasets/expect-with-engines (engines-that-support :nested-queries)
  breakout-results
  (rows+cols
    (format-rows-by [int int]
      (qp/process-query
        {:database (data/id)
         :type     :query
         :query    {:source-query {:native (format "SELECT * FROM %s" (venues-identifier))}
                    :aggregation  [:count]
                    :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}}))))

;; Make sure we can run queries using source table `card__id` format. This is the format that is actually used by the frontend;
;; it gets translated to the normal `source-query` format by middleware. It's provided as a convenience so only minimal changes
;; need to be made to the frontend.
(expect
  breakout-results
  (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                            :type     :query
                                            :query    {:source-table (data/id :venues)}}}]
    (rows+cols
      (format-rows-by [int int]
        (qp/process-query
          {:database database/virtual-id
           :type     :query
           :query    {:source-table (str "card__" (u/get-id card))
                      :aggregation  [:count]
                      :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}})))))

;; make sure `card__id`-style queries work with native source queries as well
(expect
  breakout-results
  (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                            :type     :native
                                            :native   {:query (format "SELECT * FROM %s" (venues-identifier))}}}]
    (rows+cols
      (format-rows-by [int int]
        (qp/process-query
          {:database database/virtual-id
           :type     :query
           :query    {:source-table (str "card__" (u/get-id card))
                      :aggregation  [:count]
                      :breakout     [[:field-literal (keyword (data/format-name :price)) :type/Integer]]}})))))


;; make sure we can filter by a field literal
(expect
  {:rows [[1 "Red Medicine" 4 10.0646 -165.374 3]]
   :cols [{:name "id",          :base_type :type/BigInteger}
          {:name "name",        :base_type :type/Text}
          {:name "category_id", :base_type :type/Integer}
          {:name "latitude",    :base_type :type/Float}
          {:name "longitude",   :base_type :type/Float}
          {:name "price",       :base_type :type/Integer}]}
  (rows+cols
    (qp/process-query
      {:database (data/id)
       :type     :query
       :query    {:source-query {:source-table (data/id :venues)}
                  :filter       [:= [:field-literal (data/format-name :id) :type/Integer] 1]}})))

(def ^:private ^:const ^String venues-source-sql
  (str "(SELECT \"PUBLIC\".\"VENUES\".\"ID\" AS \"ID\", \"PUBLIC\".\"VENUES\".\"NAME\" AS \"NAME\", "
       "\"PUBLIC\".\"VENUES\".\"CATEGORY_ID\" AS \"CATEGORY_ID\", \"PUBLIC\".\"VENUES\".\"LATITUDE\" AS \"LATITUDE\", "
       "\"PUBLIC\".\"VENUES\".\"LONGITUDE\" AS \"LONGITUDE\", \"PUBLIC\".\"VENUES\".\"PRICE\" AS \"PRICE\" FROM \"PUBLIC\".\"VENUES\") \"source\""))

;; make sure that dots in field literal identifiers get escaped so you can't reference fields from other tables using them
(expect
  {:query  (format "SELECT * FROM %s WHERE \"BIRD.ID\" = 1 LIMIT 10" venues-source-sql)
   :params nil}
  (qp/query->native
    {:database (data/id)
     :type     :query
     :query    {:source-query {:source-table (data/id :venues)}
                :filter       [:= [:field-literal :BIRD.ID :type/Integer] 1]
                :limit        10}}))

;; make sure that field-literals work as DateTimeFields
(expect
  {:query  (format "SELECT * FROM %s WHERE parsedatetime(formatdatetime(\"BIRD.ID\", 'YYYYww'), 'YYYYww') = 1 LIMIT 10" venues-source-sql)
   :params nil}
  (qp/query->native
    {:database (data/id)
     :type     :query
     :query    {:source-query {:source-table (data/id :venues)}
                :filter       [:= [:datetime-field [:field-literal :BIRD.ID :type/DateTime] :week] 1]
                :limit        10}}))

;; make sure that aggregation references match up to aggregations from the same level they're from
;; e.g. the ORDER BY in the source-query should refer the 'stddev' aggregation, NOT the 'avg' aggregation
(expect
  {:query (str "SELECT avg(\"stddev\") AS \"avg\" FROM ("
                   "SELECT STDDEV(\"PUBLIC\".\"VENUES\".\"ID\") AS \"stddev\", \"PUBLIC\".\"VENUES\".\"PRICE\" AS \"PRICE\" "
                   "FROM \"PUBLIC\".\"VENUES\" "
                   "GROUP BY \"PUBLIC\".\"VENUES\".\"PRICE\" "
                   "ORDER BY \"stddev\" DESC, \"PUBLIC\".\"VENUES\".\"PRICE\" ASC"
               ") \"source\"")
   :params nil}
  (qp/query->native
    {:database (data/id)
     :type     :query
     :query    {:source-query {:source-table (data/id :venues)
                               :aggregation  [[:stddev [:field-id (data/id :venues :id)]]]
                               :breakout     [[:field-id (data/id :venues :price)]]
                               :order-by     [[[:aggregate-field 0] :descending]]}
                :aggregation  [[:avg [:field-literal "stddev" :type/Integer]]]}}))

;; make sure that we handle [field-id [field-literal ...]] forms gracefully, despite that not making any sense
(expect
  {:query  (format "SELECT \"category_id\" AS \"category_id\" FROM %s GROUP BY \"category_id\" ORDER BY \"category_id\" ASC LIMIT 10" venues-source-sql)
   :params nil}
  (qp/query->native
    {:database (data/id)
     :type     :query
     :query    {:source-query {:source-table (data/id :venues)}
                :breakout     [:field-id [:field-literal "category_id" :type/Integer]]
                :limit        10}}))

;; Make sure we can filter by string fields
(expect
  {:query  (format "SELECT * FROM %s WHERE \"text\" <> ? LIMIT 10" venues-source-sql)
   :params ["Coo"]}
  (qp/query->native {:database (data/id)
                     :type     :query
                     :query    {:source-query {:source-table (data/id :venues)}
                                :limit        10
                                :filter       [:!= [:field-literal "text" :type/Text] "Coo"]}}))

;; Make sure we can filter by number fields
(expect
  {:query  (format "SELECT * FROM %s WHERE \"sender_id\" > 3 LIMIT 10" venues-source-sql)
   :params nil}
  (qp/query->native {:database (data/id)
                     :type     :query
                     :query    {:source-query {:source-table (data/id :venues)}
                                :limit        10
                                :filter       [:> [:field-literal "sender_id" :type/Integer] 3]}}))

;; make sure using a native query with default params as a source works
(expect
  {:query  "SELECT * FROM (SELECT * FROM PRODUCTS WHERE CATEGORY = 'Widget' LIMIT 10) \"source\" LIMIT 1048576",
   :params nil}
  (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                            :type     :native
                                            :native   {:query         "SELECT * FROM PRODUCTS WHERE CATEGORY = {{category}} LIMIT 10"
                                                       :template_tags {:category {:name         "category"
                                                                                  :display_name "Category"
                                                                                  :type         "text"
                                                                                  :required     true
                                                                                  :default      "Widget"}}}}}]
    (qp/query->native
      {:database (data/id)
       :type     :query
       :query    {:source-table (str "card__" (u/get-id card))}})))
