(ns shelving.query.parser
  "Implementation details of the shelving query parser."
  {:authors ["Reid \"arrdem\" McKenzie <me@arrdem.com>"],
   :license "Eclipse Public License 1.0",
   :added   "0.0.0"}
  (:require [clojure.spec.alpha :as s]
            [clojure.core.specs.alpha :as cs]
            [clojure.test.check.generators :as gen]
            [shelving.query.common :refer [lvar?]]))

(s/def ::lvar
  (s/with-gen
    lvar?
    #(gen/fmap (fn [s]
                 (symbol (str "?" s)))
               (s/gen simple-symbol?))))

(s/def ::spec
  (s/and (s/cat :from #{:from}
                :spec qualified-keyword?)
         (s/conformer
          (fn [x]
            (if (= ::s/invalid x) x
                (:spec x)))
          (fn [x]
            {:from :from :spec x}))))

(s/def ::lvar+spec?
  (s/and (s/or :simple ::lvar
               :complex (s/cat :spec  (s/? ::spec)
                        :lvar  ::lvar
                        :coll? (s/? #{'...})))
         (s/conformer
          (fn [o]
            (if (= o ::s/invalid) o
                (let [[tag e] o]
                  (if (= tag :simple)
                    {:lvar e} e))))
          (fn [{:keys [spec lvar coll?]}]
            [:complex
             (cond-> {:spec spec}
               lvar (assoc :lvar lvar)
               coll? (assoc :coll '...))]))))

(s/def ::lvars
  (s/alt :inline (s/+ ::lvar+spec?)
         :wrapped (s/coll-of ::lvar+spec? :into [])))

(s/def ::full-tuple
  (s/tuple ::lvar :shelving.core/rel-id some?))

(s/def ::terse-tuple
  (s/tuple ::lvar qualified-keyword? some?))

(s/def ::tuple
  (s/or :explicit-rel ::full-tuple
        :inferred-rel ::terse-tuple))

(s/def ::negation
  (s/and (s/cat :not #{:not}
                :clause ::clause)
         (s/conformer
          (fn [{:keys [clause]}]
            clause)
          (fn [clause]
            {:not    :not
             :clause clause}))))

(s/def ::guard
  (s/and (s/cat :guard #{:guard}
                :fn (s/with-gen any? ;; FIXME lolsob
                      #(s/gen #{'clojure.core/even? 'clojure.core/odd?}))
                :lvars (s/* ::lvar))
         (s/conformer
          (fn [{:keys [fn lvars]}]
            (cons fn lvars))
          (fn [[fn & lvars]]
            {:guard :guard
             :fn    fn
             :lvars lvars}))))

(s/def ::clause
  (s/or :tuple ::tuple
        #_:guard #_::guard
        #_:negation #_::negation))

(s/def ::clauses
  (s/alt :wrapped (s/coll-of ::clause :into [])
         :inline (s/* ::clause)))

;; seq-style datalog
;; 
;; Note that these apparently can't use conformers to simplify because they all inhabit the same seq
;; of inputs? Not sure how spec is handling the regex walk.
(s/def :shelving.query.parser.seq/find
  (s/cat :find #{:find}
         :symbols ::lvars))

(s/def :shelving.query.parser.seq/in
  (s/cat :in #{:in}
         :parameters ::lvars))

(s/def :shelving.query.parser.seq/where
  (s/cat :where #{:where}
         :rels ::clauses))

(defn- conform-datalog-seq
  "Provides a datalog front end to the Shelving query system."
  [[tag v]]
  (let [{{find :symbols}  :find
         {where :rels}    :where
         {in :parameters} :in} v]
    {:find  (or (second find) [])
     :where (or (second where) [])
     :in    (or (second in) [])}))

(defn- unform-datalog-seq
  [{:keys [find in where]}]
  [(cond (and find in where)
         :find+in+where
         (and find in (not where))
         :find+in
         (and find (not in) where)
         :find+where
         :else :find)
   {:find  {:find :find, :symbols (vector :wrapped find)}
    :in    {:in :in, :parameters (vector :wrapped in)}
    :where {:where :where, :rels (vector :wrapped where)}}])

(s/def :shelving.query.parser.seq/datalog
  ;; workaround to https://dev.clojure.org/jira/browse/CLJ-2003
  ;; just write out the possibilities as an s/alt which will unform
  (s/and (s/alt :find+in+where
                (s/cat :find  :shelving.query.parser.seq/find
                       :in    :shelving.query.parser.seq/in
                       :where :shelving.query.parser.seq/where)

                :find+in
                (s/cat :find  :shelving.query.parser.seq/find
                       :in    :shelving.query.parser.seq/in)

                :find+where
                (s/cat :find  :shelving.query.parser.seq/find
                       :where :shelving.query.parser.seq/where)

                :find
                (s/cat :find  :shelving.query.parser.seq/find))
         (s/conformer conform-datalog-seq
                      unform-datalog-seq)))

;; map-style datalog

(s/def :shelving.query.parser.map/find
  (s/coll-of ::lvar+spec? :into []))

(s/def :shelving.query.parser.map/in
  (s/coll-of ::lvar+spec? :into []))

(s/def :shelving.query.parser.map/where
  (s/coll-of ::clause :into []))

(defn- conform-datalog-map [{:keys [find in where]}]
  {:find  (or find [])
   :in    (or in [])
   :where (or where [])})

(defn- unform-datalog-map [{:keys [find in where] :as v}] v)

(s/def :shelving.query.parser.map/datalog
  (s/and (s/keys :req-un [:shelving.query.parser.map/find]
                 :opt-un [:shelving.query.parser.map/in
                          :shelving.query.parser.map/where])
         (s/conformer conform-datalog-map
                      unform-datalog-map)))

;; expose both styles as ::datalog

(s/def ::datalog
  (s/and (s/or :seq :shelving.query.parser.seq/datalog
               :map :shelving.query.parser.map/datalog)
         (s/conformer second
                      (fn [v] [:seq v]))))
