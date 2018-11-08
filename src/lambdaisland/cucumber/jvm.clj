(ns lambdaisland.cucumber.jvm
  (:require [clojure.string :as str])
  (:import cucumber.api.Result$Type
           cucumber.runner.EventBus
           [cucumber.runtime Backend BackendSupplier CucumberException FeatureSupplier RuntimeOptions]
           cucumber.runtime.io.FileResourceLoader
           cucumber.runtime.model.FeatureLoader
           [cucumber.runtime.snippets Snippet SnippetGenerator]
           io.cucumber.stepexpression.TypeRegistry
           java.util.Locale
           [io.cucumber.cucumberexpressions ParameterType Transformer]))

(def ^:dynamic *glue* nil)
(def ^:dynamic *state* nil)
(def ^:dynamic *type-registry* nil)

(defn camel->kebap [s]
  (str/join "-" (map str/lower-case (str/split s #"(?=[A-Z])"))))

(defn clojure-snippet []
  (reify
    Snippet
    (template [_]
      (str
       "({0} \"{1}\" [{3}]\n"
       "  ;; {4}\n{5}"
       "  (pending!))\n"))
    (arguments [_ argument-types] ;; map from name to type
      (->> (into {} argument-types)
           (map (comp camel->kebap key))
           (cons "state")
           (str/join " ")))
    (tableHint [_]
      "  ;; The last argument is a vector of vectors of strings.\n")
    (escapePattern [_ pattern]
      (str/replace (str pattern) "\"" "\\\""))))

(defn type-registry
  ([]
   (type-registry (Locale/getDefault)))
  ([locale]
   (TypeRegistry. locale)))

(defn register-type [registry {:cucumber.parameter/keys [name
                                                         regexp
                                                         class
                                                         transformer
                                                         suggest?
                                                         prefer-for-regexp-match?]
                               :or {suggest? true
                                    prefer-for-regexp-match? false
                                    class 'java.lang.Object}}]
  (require (symbol (namespace transformer)))
  (let [transformer (resolve transformer)
        klass (Class/forName (str class))]
    (.defineParameterType registry
                          (ParameterType. name
                                          regexp
                                          klass
                                          (reify Transformer
                                            (transform [_ s] (transformer s)))
                                          suggest?
                                          prefer-for-regexp-match?))))

(defn load-script [path]
  (try
    (load-file path)
    (catch Throwable t
      (throw (CucumberException. t)))))

(defn backend [resource-loader type-registry]
  (let [clj-snip       (clojure-snippet)
        param-type-reg (.parameterTypeRegistry type-registry)
        snip-gen       (SnippetGenerator. clj-snip param-type-reg)]

    (reify Backend
      (loadGlue [_ glue glue-paths]
        (binding [*glue* glue]
          (doseq [path     glue-paths
                  resource (.resources resource-loader path ".clj")]
            (load-script (.getPath resource)))))

      (buildWorld [this]
        (push-thread-bindings {#'*state* (atom {})}))

      (disposeWorld [this]
        (pop-thread-bindings))

      (getSnippet [this step keyword function-name-generator]
        (.getSnippet snip-gen step keyword nil)))))

(defn backend-supplier [resource-loader type-registry]
  (reify BackendSupplier (get [this] [(backend resource-loader type-registry)])))

(defn runtime-options [opts]
  (let [default (RuntimeOptions. [])]
    (proxy [RuntimeOptions] [[]]
      (^boolean isMultiThreaded []
       (> (.getThreads this) 1))
      (^List getPluginFormatterNames []
       (:plugin-formatter-names opts (.getPluginFormatterNames default)))
      (^List getPluginSummaryPrinterNames []
       (:plugin-summary-printer-names opts (.getPluginSummaryPrinterNames default)))
      (^List getPluginStepDefinitionReporterNames []
       (:plugin-step-definition-reporter-names opts (.getPluginStepDefinitionReporterNames default)))
      (^List getGlue []
       (:glue opts (.getGlue default)))
      (^boolean isStrict []
       (:strict? opts (.isStrict default)))
      (^boolean isDryRun []
       (:dry-run? opts (.isDryRun default)))
      (^boolean isWip []
       (:wip? opts (.isWip default)))
      (^List getFeaturePaths []
       (:feature-paths opts (.getFeaturePaths default)))
      (^List getNameFilters []
       (:name-filters opts (.getNameFilters default)))
      (^List getTagFilters []
       (:tag-filter opts (.getTagFilters default)))
      (^Map getLineFilters []
       (:line-filters opts (.getLineFilters default)))
      (^boolean isMonochrome []
       (:monochrome? opts (.isMonochrome default)))
      (^SnippetType getSnippetType []
       (:snippet-type opts (.getSnippetType default)))
      (^List getJunitOptions []
       (:junit-options opts (.getJunitOptions default)))
      (^int getThreads []
       (:threads opts (.getThreads default))))))

(defn resource-loader []
  (FileResourceLoader.))

(defn feature-loader []
  (FeatureLoader. (resource-loader)))

(defn feature-supplier [features]
  (reify FeatureSupplier (get [this] features)))

(defn event-bus []
  (let [events (atom [])]
    [events
     (reify EventBus
       (getTime [_]
         (System/nanoTime))
       (send [_ e]
         (swap! events conj e))
       (sendAll [_ es]
         (swap! events into es))
       (registerHandlerFor [_ _ _])
       (removeHandlerFor [_ _ _]))]))

(defn event-adaptor [state handler]
  (reify EventBus
    (getTime [_]
      (System/nanoTime))
    (send [_ e]
      (swap! state handler e))
    (sendAll [_ es]
      (swap! state #(reduce handler % es)))
    (registerHandlerFor [_ _ _])
    (removeHandlerFor [_ _ _])))

(defn runtime [opts]
  (let [registry (:type-registry opts)
        loader (resource-loader)]
    (run! (partial register-type registry) (:param-types opts))
    (.. (cucumber.runtime.Runtime/builder)
        (withRuntimeOptions (runtime-options opts))
        (withBackendSupplier (backend-supplier loader registry))
        (withFeatureSupplier (:feature-supplier opts))
        (withEventBus (:event-bus opts))
        (build))))

(defn load-features [feature-paths]
  (.load (feature-loader) feature-paths))

(defn event->type [e]
  (->> e
       class
       .getSimpleName
       camel->kebap
       (keyword "cucumber")))

(defn result->edn [r]
  {:status (condp = (.getStatus r)
             Result$Type/PASSED    :passed
             Result$Type/SKIPPED   :skipped
             Result$Type/PENDING   :pending
             Result$Type/UNDEFINED :undefined
             Result$Type/AMBIGUOUS :ambiguous
             Result$Type/FAILED    :failed)
   :duration (.getDuration r)
   :error (.getError r)})

(defn execute! [opts]
  (let [event-bus        (event-adaptor (:state opts) (:handler opts))
        feature-supplier (feature-supplier (:features opts))
        type-registry    (type-registry)
        runtime          (runtime (assoc opts
                                         :type-registry type-registry
                                         :feature-supplier feature-supplier
                                         :event-bus event-bus))]
    (binding [*type-registry* type-registry]
      (.run runtime))))

(comment

  (-> (feature-loader)
      (.load ["test/features"])
      (feature-supplier)
      (.get))


  (let [feature (-> (feature-loader)
                    (.load ["test/features"])
                    (feature-supplier)
                    (.get)
                    first)]
    ;; https://www.programcreek.com/java-api-examples/?code=mauriciotogneri/green-coffee/green-coffee-master/greencoffee/src/main/java/gherkin/pickles/Compiler.java

    (.getUri feature)
    (.getChildren (.getFeature (.getGherkinFeature feature)))
    (.getTags (.getFeature (.getGherkinFeature feature)))
    (.getName (first (.getChildren (.getFeature (.getGherkinFeature feature)))))
    (.getName (.getFeature (.getGherkinFeature feature)))
    )
  (run!)

  (let [f (-> (feature-loader)
              (.load ["test/features"])
              first
              (.getGherkinFeature)
              (.getFeature)
              )]
    f
    )

  (execute! {:features (-> (load-features ["test/features"])
                           first
                           lambdaisland.cucumber.gherkin/gherkin->edn
                           lambdaisland.cucumber.gherkin/dedupe-feature
                           second
                           lambdaisland.cucumber.gherkin/edn->gherkin
                           vector)
             :glue ["test/features/step_definitions"]})


  )
