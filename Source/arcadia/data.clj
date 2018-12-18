(ns arcadia.data
  (:require [arcadia.internal.namespace])
  (:import [System.Reflection
            FieldInfo
            ConstructorInfo
            BindingFlags
            ParameterInfo]
           Arcadia.Util
           [System TimeSpan]
           System.IO.TextWriter
           [System.Reflection Assembly]))
;; ============================================================
;; utils

;; slightly faster:
(defmacro ^:private lit-str [& xs]
  `(str
     (doto (new StringBuilder)
       ~@(for [x xs]
           (if (string? x)
             `(.Append ~x)
             `(.Append (str ~x)))))))


;; ============================================================
;; serialization flag

(def
  ^:dynamic
  ^{:doc "Determines whether objects should print in a manner that can be read back in by the edn reader. Defaults to false."}
  *serialize* false)

;; ============================================================
;; object database 
;; ============================================================

;; TODO where should this lift? 
;; TODO is the atom needed?
(defonce ^:dynamic *object-db* (atom {}))

;; This never drains - potential memory leak. Should only run in repl
;; though.
(defn db-put [^UnityEngine.Object obj]
  (let [id (.GetInstanceID obj)]
    (swap! *object-db* assoc id obj)
    id))

(defn clean-object-db
  "Remove all destroyed objects from `*object-db*`."
  []
  (swap! *object-db*
    (fn [db]
      (persistent!
        (reduce-kv
          (fn [acc id obj]
            (if (nil? (Arcadia.Util/TrueNil obj))
              (dissoc! acc id)
              acc))
          (transient db)
          db)))))

;; TODO handle nil
;; clojure errors out if this returns nil
;; considers the dispatch to have failed... 
(defn db-get [id]
  (if (contains? @*object-db* id)
    (get @*object-db* id)
    (throw
      (InvalidOperationException.
        (str "Attempting to retrieve an object corresponding to " id " from `*object-db*`, but no such object has been registered.")))))

;; ============================================================
;; installation
;; ============================================================

(defn- install-reader [name var]
  (assert (symbol? name))
  (assert (var? var))
  (alter-var-root #'clojure.core/*data-readers* assoc name var)
  ;; for the repl (bit aggressive):
  (when (.getThreadBinding ^clojure.lang.Var #'clojure.core/*data-readers*)
    (set! clojure.core/*data-readers*
      (merge clojure.core/*data-readers*
        (.getRawRoot #'clojure.core/*data-readers*)))))

;; ============================================================
;; constructor wrangling
;; ============================================================

(def
  ^{:doc "Vector of all private and public instance FieldInfos of type
 sorted by name"}
  sorted-fields
  (memoize ; speeds up instance-from-values by about 1/3
    (fn sorted-fields [type]
      (->> (.GetFields type
             (enum-or BindingFlags/Public BindingFlags/NonPublic BindingFlags/Instance))
           (sort-by #(.Name ^FieldInfo %))
           vec))))

(def keyed-fields
  (memoize
    (fn keyed-fields [type]
      (let [fs (.GetFields type
                 (enum-or BindingFlags/Public BindingFlags/NonPublic BindingFlags/Instance))]
        (zipmap
          (map #(keyword (.Name ^FieldInfo %)) fs)
          fs)))))

(defn field-values
  "Vector of values of all private and public instance fields of obj
   sorted by the names of the field"
  [obj]
  (let [type (.GetType obj)
        fields (sorted-fields type)]
    (mapv #(.GetValue ^FieldInfo % obj) fields)))

(def resolve-serialized-type
  (memoize
    (fn resolve-serialized-type [st]
      (let [s (symbol (str "UnityEngine." st))
            t (resolve s)]
        (or t
            (throw
              (ArgumentException.
                (str "Symbol " s " does not resolve."))))))))

(defn instance-from-values
  "Create an instance of type from the values vector, assumed
  to be sorted by the name of the fields (as generated by field-values)"
  [type values]
  (let [type (resolve-serialized-type type) ; bad that we have to do this, find root problem upstream
        obj (Activator/CreateInstance type)
        fields (sorted-fields type)]
    ;;(arcadia.debug/break)
    (loop [i (int 0)]
      (when (< i (count fields))
        (let [^FieldInfo field (nth fields i)
              val (nth values i)]
          (.SetValue field obj
            (Convert/ChangeType
              val
              (.FieldType field)))
          (recur (inc i)))))
    obj))

(defn instance-from-values-map
  [{:keys [::type] :as values}]
  (let [type (resolve-serialized-type type)
        obj (Activator/CreateInstance type)
        fields (keyed-fields type)]
    (reduce-kv
      (fn [_ k v]
        (when-not (= ::type k)
          (if-let [^FieldInfo field (get fields k)]
            (.SetValue field obj
              (Convert/ChangeType v (.FieldType field)))
            (throw
              (ArgumentException.
                (str "No corresponding field found in " type " for field key " k))))))
      nil
      values)
    obj))

;; ============================================================
;; value types
;; ============================================================

(defn type-symbol [t]
  (cond (symbol? t) t
        (isa? (type t) Type) (let [^Type t t] (symbol (.FullName t)))
        :else (throw (Exception. (str t " is not a type or a symbol")))))

(defn- obsolete? [t]
  (some #(instance? ObsoleteAttribute %) (.GetCustomAttributes t false)))

(def value-types
  (->> UnityEngine.Vector3
       .Assembly
       .GetTypes
       (filter #(.IsValueType ^Type %))
       (filter #(.IsVisible ^Type %))
       (filter #(= "UnityEngine" (.Namespace ^Type %)))
       (remove obsolete?)
       (remove #(.IsEnum ^Type %))
       (remove #(.IsNested ^Type %))))

;; ============================================================
;; print things
;; ============================================================

(defonce print-hierarchy (atom (make-hierarchy)))

(swap! print-hierarchy
  (fn [h]
    (letfn [(rfn [h, ^System.Type t]
              (derive h t ::unity-value-type))]
      (reduce rfn h value-types))))

;; ------------------------------------------------------------
;; print-unserialized

(defn print-unserialized-dispatch [x _]
  (class x))

(defmulti print-unserialized #'print-unserialized-dispatch
  :hierarchy print-hierarchy
  :default ::default)

(defmethod print-unserialized ::default [x ^System.IO.TextWriter w]
  (.Write w
    (lit-str "#<" (class x) " " x ">")))

;; ------------------------------------------------------------
;; serialize

(defn serialize-dispatch [x _]
  (class x))

(defmulti serialize #'serialize-dispatch
  :hierarchy print-hierarchy
  :default ::default)

(defmethod serialize ::default [x _]
  (throw
    (ArgumentException.
      (str "No implementation of `arcadia.data/serialize` found for class " (class x)))))

(defn arcadia-print [x w]
  (if *serialize*
    (serialize x w)
    (print-unserialized x w)))

;; doing this because we don't want to mutate the global hierarchy
;; just yet
(doseq [t value-types]
  (.addMethod ^clojure.lang.MultiFn print-method t arcadia-print))

;; ------------------------------------------------------------
;; print value types

(defn vt-serialize-vec [x ^System.IO.TextWriter w]
  (let [sb (new StringBuilder "#unity/value")
        t (class x)
        fs (sorted-fields t)]
    (doto sb
      (.Append "[\"")
      (.Append (.Name t))
      (.Append "\""))
    (loop [i (int 0)]
      (when (< i (count fs))
        (let [^FieldInfo f (nth fs i)]
          (.Append sb ",")
          (.Append sb (str (.GetValue f x)))
          (recur (inc i)))))
    (.Append sb "]")
    (.Write w (str sb))))

(defn vt-serialize-map [x ^System.IO.TextWriter w]
  (let [t (class x)
        fs (sorted-fields t)
        ^StringBuilder sb (new StringBuilder "#unity/value")]
    (doto sb
      (.Append "{:arcadia.data/type \"")
      (.Append (.Name t))
      (.Append "\""))
    (.Write w
      (loop [i (int 0)]
        (if (< i (count fs))
          (let [^FieldInfo f (nth fs i)]
            (.Append sb ",")
            (doto sb
              (.Append ":")
              (.Append (.Name f))
              (.Append " ")
              (.Append (.GetValue f x)))
            (recur (inc i)))
          (do (.Append sb "}")
              (str sb)))))))

(defmethod serialize ::unity-value-type [x w]
  (if *print-readably*
    (vt-serialize-map x w)
    (vt-serialize-vec x w)))

(doseq [t value-types]
  (.addMethod ^clojure.lang.MultiFn print-method t arcadia-print))

;; ;; we don't want to mutate the global hierarchy for this
;; (doseq [t value-types]
;;   (.addMethod ^clojure.lang.MultiFn print-dup t value-type-print-dup))

;; don't want to mutate the global hierarchy for this

;; ============================================================
;; read value types printed with *print-dup* back


;; We need to do this because `derive` doesn't work with strings.
;; Don't want to memoize it because that might enable attacks on
;; memory for certain edn strings.
(defonce value-type-string->type
  (atom
    (persistent!
      (reduce
        (fn [acc ^Type t]
          (assoc! acc (.Name t) t))
        (transient {})
        value-types))))

(defn read-value-type-dispatch [args]
  (get @value-type-string->type
    (cond
      (vector? args)
      (nth args 0)
      
      (map? args)
      (get args ::type)

      :else (throw
              (ArgumentException.
                (str "Expects vector or map, instead got " (class args)))))))

;; install the dispatch function itself rather than a reference to it
;; when we're confident
(defmulti read-value-type #'read-value-type-dispatch
  :hierarchy print-hierarchy)

(defmethod read-value-type ::unity-value-type [args]
  (cond
    (vector? args)
    (instance-from-values (nth args 0) (subvec args 1))

    (map? args)
    (instance-from-values-map args)

    :else (throw
            (ArgumentException.
              (str "Expects vector or map, instead got " (class args))))))

(install-reader 'unity/value #'read-value-type)

;; ============================================================
;; custom serialization/deserialization for common types

;; Note that the vector representation is still sorted
;; by field name here. This means we can go back and
;; add custom extensions for other types without breaking
;; anything.
(defmacro ^:private standard-extension [type-sym fields]
  (let [shortname (-> type-sym resolve (.Name))
        sorted (sort fields)
        value-sym (with-meta (gensym "value_") {:tag type-sym})]
    `(do
       (defmethod read-value-type ~type-sym [~'args]
         (cond
           (vector? ~'args)
           (let [[_# ~@sorted] ~'args]
             (new ~type-sym ~@fields))

           (map? ~'args)
           (let [{:keys [~@fields]} ~'args]
             (new ~type-sym ~@fields))

           :else
           (throw
             (ArgumentException.
               (str "Expects vector or map, instead got " (class ~'args))))))
       
       (defmethod serialize ~type-sym [^UnityEngine.Vector3 ~value-sym, ^TextWriter w#]
         (.Write w#
           (if *print-readably*
             (lit-str
               ~(str "#unity/value{:arcadia.data/type \"" shortname "\"")
               ~@(apply concat
                   (for [field fields]
                     [(str ",:" field " ") `(. ~value-sym ~field)]))         
               "}")
             (lit-str
               ~(str "#unity/value[\"" shortname "\"")
               ~@(apply concat
                   (for [field sorted]
                     ["," `(. ~value-sym ~field)]))
               "]")))))))

(standard-extension UnityEngine.Vector2 [x y])
(standard-extension UnityEngine.Vector3 [x y z])
(standard-extension UnityEngine.Vector4 [x y z w])
(standard-extension UnityEngine.Color [r g b a])
(standard-extension UnityEngine.Quaternion [x y z w])

;; ============================================================
;; object types
;; ============================================================

(defmethod print-method UnityEngine.Object [x w]
  (arcadia-print x w))

(defmethod print-unserialized UnityEngine.Object [^UnityEngine.Object x, ^System.IO.TextWriter stream]
  (.Write stream
    (lit-str "#<" x ">")))

(defmethod serialize UnityEngine.Object [^UnityEngine.Object v ^System.IO.TextWriter w]
  (.Write w
    (lit-str "#unity/object[" (class v) " " (db-put v) "]")))

(defn read-object [[_ id]]
  (db-get id))

(install-reader 'unity/object #'read-object)

;; ============================================================
;; for defmutable:

(defn- read-user-type-dispatch [{:keys [::type]}]
  type)

(defmulti read-user-type
  read-user-type-dispatch
  :default ::default)

(defmethod read-user-type ::default [{t ::type
                                       :as spec}]
  (cond
    (nil? t)
    (throw
      (ArgumentException. "No value found for key `::arcadia.data/type`"))
    
    (not (symbol? t))
    (throw
      (ArgumentException. (str "Value for key `::arcadia.data/type` must be a symbol, instead got " (class t)))))
  (let [ns-name (symbol (Arcadia.Util/TypeNameToNamespaceName (name t)))]
    (arcadia.internal.namespace/quickquire ns-name)
    (if (contains? (methods read-user-type) (read-user-type-dispatch spec))
      (read-user-type spec)
      (throw
        (ArgumentException.
          (str
            "`arcadia.data/read-user-type` multimethod extension cannot be found for `::arcadia.data/type` value " t))))))

(install-reader 'arcadia.data/data #'read-user-type)