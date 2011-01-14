(ns logos.minikanren
  (:refer-clojure :exclude [reify ==])
  (:use [clojure.pprint :only [pprint]]))

(def ^:dynamic *occurs-check* true)

;; =============================================================================
;; Logic Variables

(deftype lvarT [name s]
  Object
  (toString [this] (str "<lvar:" name ">")))

(defn ^lvarT lvar
  ([] (lvarT. (gensym) nil))
  ([name] (lvarT. name nil))
  ([name s] (lvarT. name s)))

(defmethod print-method lvarT [x writer]
  (.write writer (str "<lvar:" (.name ^lvarT x) ">")))

(defn lvar? [x]
  (instance? lvarT x))

;; =============================================================================
;; LCons

(defprotocol LConsSeq
  (lfirst [this])
  (lnext [this]))

;; TODO: clean up the printing code

(defprotocol LConsPrint
  (toShortString [this]))

(declare lcons?)

;; TODO: LCons is Sequential

(deftype LCons [a d cache]
  LConsSeq
  (lfirst [_] a)
  (lnext [_] d)
  LConsPrint
  (toShortString [this]
                 (cond
                  (instance? LCons d) (str a " " (toShortString d))
                  :else (str a " . " d )))
  Object
  (toString [this] (cond
                    (instance? LCons d) (str "(" a " " (toShortString d) ")")
                    :else (str "(" a " . " d ")")))
  (equals [this o]
          (or (identical? this o)
              (and (lcons? o)
                   (loop [me this
                          you o]
                     (cond
                      (nil? me) (nil? you)
                      (lvar? me) true
                      (lvar? you) true
                      :else (let [mef  (lfirst me)
                                  youf (lfirst you)]
                              (and (or (= mef youf)
                                       (lvar? mef)
                                       (lvar? youf))
                                   (recur (lnext me) (lnext you)))))))))

  (hashCode [this]
            (if @cache
              @cache
              (loop [hash 1 xs this]
                (if (or (nil? xs) (lvar? xs))
                  (reset! cache hash)
                  (let [val (lfirst xs)]
                    (recur (unchecked-add-int
                            (unchecked-multiply-int 31 hash)
                            (clojure.lang.Util/hash val))
                           (lnext xs))))))))

(defmethod print-method LCons [x writer]
  (.write writer (str x)))

(defn lcons [a d]
  (if (or (coll? d) (nil? d))
    (cons a (seq d))
    (LCons. a d (atom nil))))

(defn lcons? [x]
  (instance? LCons x))

(extend-protocol LConsSeq
  clojure.lang.IPersistentCollection
  (lfirst [this] (clojure.core/first this))
  (lnext [this] (clojure.core/next this)))

(defmacro llist
  ([f s] `(lcons ~f ~s))
  ([f s & rest] `(lcons ~f (llist ~s ~@rest))))

;; TODO: convert to macro

(defn lcoll? [x]
  (or (lcons? x)
      (and (coll? x) (seq x))))

;; =============================================================================
;; Substitutions

(defprotocol ISubstitutions
  (length [this])
  (occurs-check [this u v])
  (ext [this x v])
  (ext-no-check [this x v])
  (walk [this v])
  (walk* [this v])
  (unify [this u v])
  (unify-seq [this u v in-seq])
  (reify-lvar-name [_])
  (-reify [this v])
  (reify [this v]))

(declare empty-s)

(deftype Substitutions [s]
  ISubstitutions

  (length [this] (count s))

  ;; TODO : revisit recur here

  (occurs-check [this u v]
                (cond
                 (lvar? v) (= (walk this v) v)
                 (lcoll? v) (or (occurs-check this u (lfirst v))
                                (occurs-check this u (lnext v))))
                :else false)
  
  (ext [this u v]
       (if (and *occurs-check* (occurs-check this u v))
         this
         (ext-no-check this u v)))

  (ext-no-check [this u v]
                (Substitutions. (assoc s u v)))

  (walk [this v]
        (loop [v' v lv nil]
          (cond
           (identical? v' ::not-found) lv
           (not (lvar? v')) v'
           :else (recur (get s v' ::not-found) v'))))

  ;; TODO : revisit recur here. Main issue was how to reconstruct
  ;; types ?

  (walk* [this v]
         (let [v' (walk this v)]
           (cond
            (lvar? v') v'
            (lcoll? v') (let [vseq (if (map? v') (reduce concat v') v')
                              vf (walk* this (lfirst vseq))
                              vn (walk* this (lnext vseq))
                              r (lcons vf vn)]
                          (cond
                           (vector? v') (vec r)
                           (map? v') (apply hash-map r)
                           (set? v') (set r)
                           :else r))
            :else v')))

  (unify [this u v] (unify-seq this u v false))

  ;; TODO : for sequences this unnecessarily stack-consuming
  ;; as well as checking for conditions that don't matter
  ;; for sequences

  ;; TODO : wow, we can really, really, really speed up unification
  ;;
  ;; a) declare IUnifyLeft protocol
  ;;    (unify-left u v s)
  ;; b) declare default implementation for Object
  ;; c) we flip args from b to look for a match on the other side
  ;;    IUnifyRight protocol, whose implementations look just like
  ;;    if Object, just do equality check
  ;;    (unify-right v u s)
  ;; d) implementation for lvarT, Sequential, PersistentHashMap, PersistentHashSet
  ;;    flip args and dispatch on second arg
  ;;
  ;; this means we have n^2 possible matches, in our case 25
  ;;
  ;; (if (identical? u v) this (unify-terms u v this))

  (unify-seq [this u v in-seq]
             (if (identical? u v)
               this
               (let [u (walk this u)
                     v (walk this v)]
                 (cond
                  (identical? u v) this
                  (lvar? u) (cond
                             (lvar? v) (ext-no-check this u v)
                             (and in-seq (nil? v)) (ext this u '())
                             :else (ext this u v))
                  (lvar? v) (if (and in-seq (nil? u))
                              (ext this v '())
                              (ext this v u))
                  (and (lcoll? u) (lcoll? v)) (let [uf (lfirst u)
                                                    ur (lnext u)
                                                    vf (lfirst v)
                                                    vr (lnext v)]
                                                (let [s (unify this uf vf)]
                                                  (and s (unify-seq s ur vr true))))
                  (= u v) this
                  :else false))))

  ;; (unify [this u v in]
  ;;        (if (identical? u v)
  ;;          this
  ;;          (let [u (walk this u)
  ;;                v (walk this v)]
  ;;            (if (identical? u v) this (unify-terms u v this)))))

  (reify-lvar-name [this]
                   (symbol (str "_." (count s))))

  ;; TODO : unnecessarily stack consuming

  (-reify [this v]
          (let [v (walk this v)]
            (cond
             (lvar? v) (ext this v (reify-lvar-name this))
             (lcoll? v) (-reify (-reify this (lfirst v)) (lnext v))
             :else this)))

  (reify [this v]
         (let [v (walk* this v)]
           (walk* (-reify empty-s v) v))))

(def empty-s (Substitutions. {}))

(defn subst? [x]
  (instance? Substitutions x))

(defn to-s [v]
  (let [s (reduce (fn [m [k v]] (assoc m k v)) {} v)]
    (Substitutions. s)))

;; =============================================================================
;; Unification

(defprotocol IUnifyTerms
  (unify-terms [u v s]))

(defprotocol IUnifyWithObject
  (unify-with-object [v u s]))

(defprotocol IUnifyWithLVar
  (unify-with-lvar [v u s]))

(defprotocol IUnifyWithSequential
  (unify-with-seq [v u s]))

(defprotocol IUnifyWithMap
  (unify-with-map [v u s]))

(defprotocol IUnifyWithSet
  (unify-with-set [v u s]))

;; -----------------------------------------------------------------------------
;; Unify Object with X

(extend-type Object
  IUnifyTerms
  (unify-terms [u v s]
    (unify-with-object v u s)))

(extend-type lvarT
  IUnifyWithObject
  (unify-with-object [v u s]
    (ext s u v)))

(extend-protocol IUnifyWithObject
  clojure.lang.Sequential
  (unify-with-object [v u s] false))

(extend-protocol IUnifyWithObject
  clojure.lang.IPersistentMap
  (unify-with-object [v u s] false))

(extend-protocol IUnifyWithObject
  clojure.lang.IPersistentSet
  (unify-with-object [v u s] false))

(extend-type Object
  IUnifyWithObject
  (unify-with-object [v u s]
    (if (= v u) s)))

;; -----------------------------------------------------------------------------
;; Unify lvarT with X

(extend-type lvarT
  IUnifyTerms
  (unify-terms [u v s]
    (unify-with-lvar v u s)))

(extend-type Object
  IUnifyWithLVar
  (unify-with-lvar [v u s]
    (ext s u v)))

(extend-type lvarT
  IUnifyWithLVar
  (unify-with-lvar [v u s]
    (ext-no-check s u v)))

(extend-protocol IUnifyWithLVar
  clojure.lang.Sequential
  (unify-with-lvar [v u s]
    (ext s u v)))

(extend-protocol IUnifyWithLVar
  clojure.lang.IPersistentMap
  (unify-with-lvar [v u s]
    (ext s u v)))

(extend-protocol IUnifyWithLVar
  clojure.lang.IPersistentSet
  (unify-with-lvar [v u s]
    (ext s u v)))

;; -----------------------------------------------------------------------------
;; Unify Sequential with X

(extend-protocol IUnifyTerms
  clojure.lang.Sequential
  (unify-terms [u v s]
    (unify-with-seq v u s)))

(extend-type Object
  IUnifyWithSequential
  (unify-with-seq [v u s] false))

(extend-type lvarT
  IUnifyWithSequential
  (unify-with-seq [v u s]
    (ext s v u)))

(extend-protocol IUnifyWithSequential
  clojure.lang.IPersistentMap
  (unify-with-seq [v u s] false))

(extend-protocol IUnifyWithSequential
  clojure.lang.IPersistentSet
  (unify-with-seq [v u s] false))

(extend-protocol IUnifyWithSequential
  clojure.lang.Sequential
  (unify-with-seq [v u s]
    ;; ... yup ...
    ))

;; -----------------------------------------------------------------------------
;; Unify PersistentHashMap with X

(extend-protocol IUnifyTerms
  clojure.lang.IPersistentMap
  (unify-terms [u v s]
    (unify-with-map v u s)))

(extend-type Object
  IUnifyWithMap
  (unify-with-map [v u s] false))

(extend-type lvarT
  IUnifyWithMap
  (unify-with-map [v u s]
    (ext s v u)))

(extend-protocol IUnifyWithMap
  clojure.lang.Sequential
  (unify-with-map [v u s] false))

;; same length
;; iterate over keys
;; they must exist or the number of missing keys
;; same as set but constraint on keys *and* values

(extend-protocol IUnifyWithMap
  clojure.lang.IPersistentMap
  (unify-with-map [v u s]
    ;; ... yup ...
    ))

(extend-protocol IUnifyWithMap
  clojure.lang.IPersistentSet
  (unify-with-map [v u s] false))

;; -----------------------------------------------------------------------------
;; Unify PersistentHashSet with X

(defprotocol IUnifyWithSet
  (unify-with-set [v u s]))

(extend-protocol IUnifyTerms
  clojure.lang.IPersistentSet
  (unify-terms [u v s]
    (unify-with-set v u s)))

(extend-type Object
  IUnifyWithSet
  (unify-with-map [v u s] false))

(extend-type lvarT
  IUnifyWithSet
  (unify-with-map [v u s]
    (ext s v u)))

(extend-protocol IUnifyWithSet
  clojure.lang.Sequential
  (unify-with-set [v u s] false))

(extend-protocol IUnifyWithSet
  clojure.lang.IPersistentMap
  (unify-with-set [v u s] false))

;; same length
;; the difference should only contain lvars

(extend-protocol IUnifyWithSet
  clojure.lang.IPersistentSet
  (unify-with-set [v u s]
    ;; ... yup ...
    ))

;; =============================================================================
;; Goals and Goal Constructors

(defprotocol IBind
  (bind [this g]))

(defprotocol IMPlus
  (mplus [a b]))

(defmacro bind*
  ([a g] `(bind ~a ~g))
  ([a g & g-rest]
     `(bind* (bind ~a ~g) ~@g-rest)))

(defmacro mplus*
  ([e] e)
  ([e & e-rest]
     `(mplus ~e (lazy-seq (let [r# (mplus* ~@e-rest)]
                            (cond
                             (subst? r#) (cons r# nil)
                             :else r#))))))

;; -----------------------------------------------------------------------------
;; MZero

(extend-protocol IBind
  nil
  (bind [_ g] nil))

(extend-protocol IMPlus
  nil
  (mplus [_ b] b))

;; -----------------------------------------------------------------------------
;; Unit

(defprotocol IMPlusUnit
  (mplus-u [this a]))

(extend-type Substitutions
  IBind
  (bind [this g]
        (g this))
  IMPlus
  (mplus [this b]
         (mplus-u b this)))

(extend-protocol IMPlusUnit
  nil
  (mplus-u [_ a] a))

(extend-type Substitutions
  IMPlusUnit
  (mplus-u [this a]
           (list a this)))

(extend-protocol IMPlusUnit
  clojure.lang.ISeq
  (mplus-u [this a]
           (cons a this)))

;; -----------------------------------------------------------------------------
;; Stream

(extend-protocol IBind
  clojure.lang.ISeq
  (bind [this g]
        (if-let [r (seq (map g this))]
          (reduce mplus r))))

(defprotocol IMPlusStream
  (mplus-s [this a]))

(extend-protocol IMPlus
  clojure.lang.ISeq
  (mplus [this b]
         (mplus-s b this)))

(extend-protocol IMPlusStream
  nil
  (mplus-s [_ a] a))

(extend-type Substitutions
  IMPlusStream
  (mplus-s [this a] (cons this a)))

(extend-protocol IMPlusStream
  clojure.lang.ISeq
  (mplus-s [this a]
           (cons (first a)
                 (cons (first this)
                       (mplus* (next this) (next a))))))

;; =============================================================================
;; Syntax

(defn succeed [a] a)

(defn fail [a] nil)

(def s* succeed)

(def u* fail)

(defmacro == [u v]
  `(fn [a#]
     (if-let [a'# (unify a# ~u ~v)]
       a'#
       nil)))

(defn bind-cond-e-clause [a]
  (fn [g-rest]
    `(bind* ~a ~@g-rest)))

(defn bind-cond-e-clauses [a clauses]
  (map (bind-cond-e-clause a) clauses))

(defmacro cond-e [& clauses]
  (let [a (gensym "a")]
    `(fn [~a]
       (lazy-seq
        (mplus* ~@(bind-cond-e-clauses a clauses))))))

(defn lvar-bind [sym]
  ((juxt identity
         (fn [s] `(lvar '~s))) sym))

(defn lvar-binds [syms]
  (mapcat lvar-bind syms))

(defmacro exist [[& x-rest] & g-rest]
  `(fn [a#]
     (let [~@(lvar-binds x-rest)]
       (bind* a# ~@g-rest))))

(defn reifier [lvar]
  (fn [a]
    (reify a lvar)))

(defn to-seq [x]
  (cond
   (nil? x) '()
   (seq? x) x
   :else (list x)))

(defmacro run [& [n [x] & g-rest]]
  `(let [r# (let [~x (lvar '~x)]
              (->> (to-seq ((fn [a#] (bind* a# ~@g-rest)) empty-s))
                   (remove nil?)
                   (map (reifier ~x))))]
    (if ~n (take ~n r#) r#)))

(defmacro run* [& body]
  `(run false ~@body))

;; TODO : fix when we get scopes

(defmacro run-nc [& [n [x] & g-rest]]
  `(binding [*occurs-check* false]
     (doall (run ~n [~x] ~@g-rest))))

(defmacro run-nc* [& body]
  `(run-nc false ~@body))

(defn sym->lvar [sym]
  `(lvar '~sym))

(defmacro all
  ([] `s*)
  ([& g-rest] `(fn [a#] (bind* a# ~@g-rest))))

;; =============================================================================
;; Debugging

(def ^:dynamic *debug* (atom []))

(defmacro trace [a & lm]
  `(binding [*debug* (or ~a *debug*)]
     ~@lm))

(defn trace-lvar [a lvar]
  `(swap! *debug* conj (format "%5s = %s\n" (str '~lvar) (reify ~a ~lvar))))

(defmacro log [title & exprs]
  `(do
     (swap! *debug* conj (str ~title "\n"))
     (swap! *debug* conj (str ~@exprs "\n"))
     (swap! *debug* conj "\n")))

(defmacro trace-lvars [title & lvars]
  (let [a (gensym "a")]
    `(fn [~a]
       (swap! *debug* conj (str ~title "\n"))
       ~@(map (partial trace-lvar a) lvars)
       (swap! *debug* conj "\n")
       ~a)))

(defn print-debug [a]
  (println (reduce str @a)))

(defmacro trace-s []
  (let [a (gensym "a")]
   `(fn [~a]
      (swap! *debug* conj ~a)
      ~a)))