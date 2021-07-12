(ns net.tiuhti.cypress-cljs
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["string_decoder" :as st]
            ["process" :as process]
            ["path" :as path]
            ["chokidar" :as chokidar]
            ["events" :as EventEmitter]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cljs.pprint :refer [pprint]]
            [meta-merge.core :as m]
            ["net" :as node-net]
            ["@cypress/browserify-preprocessor" :as browserify-preprocessor]))

(def build-hook
  "
(ns watch
  (:import (java.util.concurrent LinkedBlockingQueue)))

(def flush-queue (LinkedBlockingQueue.))

(defn hook
  {:shadow.build/stage :flush}
  [build-state]
  (.put flush-queue (:shadow.build/build-id build-state))
  build-state)
")

(def working-directory ".preprocessor-cljs")

(def shadow-cljs-bin-path "../node_modules/.bin/shadow-cljs")

(def config-path (str working-directory "/" "shadow-cljs.edn"))

(def hook-path (str working-directory "/" "watch.clj"))

(def cli-repl-port-path (str working-directory "/" ".shadow-cljs/cli-repl.port"))

(def default-config
  {:dependencies [['mocha-latte "0.1.2"]
                  ['chai-latte "0.2.0"]]
   :builds       {}})

(def override-config-path "shadow-cljs-override.edn")

(defn stat [dir]
  (let [stat (.statSync fs dir)]
    {:file-name dir
     :directory? (.isDirectory stat)}))

(defn read-dir [dir]
  (let [parent (if (string? dir)
                 (stat dir)
                 dir)]
    (map (fn [dirent]
           {:file-name (.-name dirent)
            :directory? (.isDirectory dirent)
            :path (str (or (:path parent) (:file-name parent)) "/" (.-name dirent))})
         (.readdirSync fs
                       (or (:path parent) (:file-name parent))
                       (clj->js {:withFileTypes true})))))

(def decoder (st/StringDecoder. "utf8"))

(defn buffer->str [buf]
  (.write decoder buf))

(defn compile [build-ids]
  (let [process (cp/spawn shadow-cljs-bin-path
                          (clj->js (into ["compile"] build-ids))
                          #js {:cwd working-directory})]
    (-> ^EventEmitter (.-stdout process)
        (.on "data" (fn [data]
                      (println (str "Compile: " (.trimEnd (buffer->str data)))))))
    process))

(defn namespace-symbol [test-file]
  (-> test-file
      (str/replace #"\.cljs$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn write-edn [path content]
  (.writeFileSync fs path (with-out-str (pprint content))))

(defn read-edn [path]
  (edn/read-string (buffer->str (.readFileSync fs path))))

(defn make-shadow-cljs-config [test-files integration-folder]
  (let [builds (reduce (fn [acc test-file]
                         (let [paths      (-> test-file
                                              (.split "/"))
                               test-name  (-> paths
                                              last
                                              (.split ".")
                                              first)
                               output-dir (str "out/" test-name)
                               entry      (namespace-symbol test-file)
                               build-id   (keyword test-name)]
                           (assoc acc
                                  build-id
                                  {:target      :browser
                                   :output-dir  output-dir
                                   :asset-path  (str "/" output-dir)
                                   :modules     {build-id {:entries [entry]}}
                                   :build-hooks ['(watch/hook)]})))
                       {}
                       test-files)
        config (-> default-config
                   (assoc :source-paths ["." integration-folder])
                   (assoc :builds builds)
                   (m/meta-merge (when (.existsSync fs override-config-path)
                                   (read-edn override-config-path))))]
    config))

(def watchers (atom {}))

(defn make-cljs-preprocessor [preprocessor-config]
  (let [integration-folder      (.-integrationFolder ^js preprocessor-config)
        relative-to-integration (fn [path]
                                  (.replace path (str integration-folder "/") ""))
        test-files              (->> (tree-seq :directory? read-dir (stat integration-folder))
                                     (keep :path)
                                     (remove #(.startsWith % "#")) ;; Ignore Emacs temp files
                                     (filter #(.endsWith % ".cljs"))
                                     (map relative-to-integration))
        default-preprocessor    (browserify-preprocessor)
        config                  (make-shadow-cljs-config test-files integration-folder)]
    (when-not (.existsSync fs working-directory)
      (.mkdirSync fs working-directory))
    (write-edn config-path config)
    (.writeFileSync fs hook-path build-hook)
    (println "Starting shadow-cljs server")
    (let [shadow-process    (cp/spawn shadow-cljs-bin-path #js ["server"] #js {:cwd working-directory})
          ready             (atom false)
          socket            (atom nil)
          output-fn         (fn [data]
                              (let [s (buffer->str data)]
                             (when (.includes s "server version")
                               (reset! ready true))
                             (println (.trimEnd s))))
          stopping          (atom false)
          build-poller      (atom nil)
          stop-fn           (fn []
                           (when @stopping
                             (println "Stop in progress"))
                           (js/clearInterval @build-poller)
                           (.destroy ^node-net/Socket @socket)
                           (when-not @stopping
                             (reset! stopping true)
                             (println "Stopping shadow-cljs server")
                             (let [output (cp/spawnSync shadow-cljs-bin-path #js ["stop"] #js {:cwd working-directory})]
                               (println "stdout:" (.trimEnd (buffer->str (.-stdout output))))
                               (println "stderr:" (.trimEnd (buffer->str (.-stderr output)))))
                             (println "Stopped shadow-cljs server")
                             (process/exit 0)))
          active-builds     (atom #{})
          build-id->resolve (atom {})
          build-id->rerun   (atom {})
          build-id->file    (atom {})
          deliver-now       (atom false)]
      (add-watch ready :socket (fn []
                                 (let [s (node-net/connect #js {:port    (js/parseInt (buffer->str (.readFileSync fs cli-repl-port-path)))
                                                                :host    "localhost"
                                                                :timeout 1000})]
                                   (.on s "end" #(println "Control socket closed"))
                                   (.on s "data" (fn [data]
                                                   (let [string (buffer->str data)]
                                                     (try
                                                       (let [{:keys [event-id data] :as event} (edn/read-string string)]
                                                         (case event-id
                                                           :active-builds (reset! active-builds data)
                                                           :flush         (let [build-id data]
                                                                            (when-let [resolve-fn (get @build-id->resolve build-id)]
                                                                              (println "Delivering compile result")
                                                                              (resolve-fn))
                                                                            (when-let [rerun-fn (get @build-id->rerun build-id)]
                                                                              (println "Compile done, rerunning test")
                                                                              (rerun-fn))
                                                                            (when-let [file (get @build-id->file build-id)]
                                                                              (when (and (not (get @build-id->rerun build-id))
                                                                                         (not (get @build-id->resolve build-id))
                                                                                         (contains? @active-builds build-id))
                                                                                (println "Switching to cljs repl")
                                                                                (reset! deliver-now true)
                                                                                (.emit ^js file "rerun")))
                                                                            (swap! build-id->resolve dissoc build-id)
                                                                            (swap! build-id->rerun dissoc build-id))
                                                           :error         (println "Error" data)
                                                           (println "Unknown event" event)))
                                                       (catch :default _
                                                         (println "Failed to parse control message:" string))))))
                                   (.write s "(require '[watch :as watch])\n (future (try (loop [] (println {:event-id :flush :data (.take watch/flush-queue)}) (recur)) (catch Throwable t (println {:event-id :error :data :flush-queue-read}))))\n")
                                   (reset! build-poller (js/setInterval #(.write s "(println {:event-id :active-builds :data (shadow/active-builds)})\n")
                                                                        1000))
                                   (reset! socket s))))
      ;; TODO: Option for compiling all tests at start
      #_(add-watch ready :compile (fn [_]
                                  (compile (map name (keys builds)))))
      (-> ^EventEmitter (.-stdout shadow-process)
          (.on "data" output-fn))
      (-> ^EventEmitter (.-stderr shadow-process)
          (.on "data" output-fn))
      (.on process "SIGINT" stop-fn)
      (.on process "SIGTERM" stop-fn)
      (fn preprocessor [file]
        (let [filePath (.-filePath ^js file)]
          (if-not (.endsWith filePath ".cljs")
            (default-preprocessor file)
            (let [test-name     (-> filePath
                                    (.split "/")
                                    last
                                    (.split ".")
                                    first)
                  compiled-file (str/join [(path/resolve working-directory (str "out/" test-name))
                                           "/"
                                           test-name
                                           ".js"])
                  build-id      (keyword test-name)
                  test-file     (relative-to-integration filePath)
                  config        (read-edn config-path)]
              (swap! build-id->file assoc build-id file)
              (when-not (contains? (:builds config) build-id)
                (println (str "Adding build " build-id " to shadow-cljs configuration"))
                (let [output-dir (str "out/" test-name)
                      config     (update config :builds conj [build-id {:target      :browser
                                                                        :output-dir  output-dir
                                                                        :asset-path  (str "/" output-dir)
                                                                        :modules     {build-id {:entries [(namespace-symbol test-file)]}}
                                                                        :build-hooks ['(watch/hook)]}])]
                  (write-edn config-path config)))
              (when (and (.-shouldWatch ^js file)
                         (not (contains? @watchers filePath)))
                (println "Add watcher for" filePath)
                (swap! watchers assoc filePath {:watcher       (let [watcher (chokidar/watch filePath)]
                                                                 (.on watcher "change" (fn [path]
                                                                                         (swap! build-id->rerun assoc build-id #(.emit ^js file "rerun"))
                                                                                         (if (contains? @active-builds build-id)
                                                                                           (println path "is being watched shadow-cljs, skipping compile")
                                                                                           (do
                                                                                             (println path "changed, recompiling")
                                                                                             (compile [(name build-id)])))))
                                                                 watcher)
                                                :compiled-file compiled-file})
                (.on ^EventEmitter file "close" (fn []
                                                  (println "Remove watcher for" filePath)
                                                  (swap! build-id->file dissoc build-id)
                                                  (when-let [{:keys [watcher]} (get @watchers filePath)]
                                                    (.close watcher))
                                                  (swap! watchers dissoc filePath)
                                                  true)))
              (js/Promise. (fn [resolve-fn _reject]
                             (if @deliver-now
                               (do
                                 (println "Compilation result already present, delivering")
                                 (reset! deliver-now false)
                                 (resolve-fn compiled-file))
                               (do
                                 (swap! build-id->resolve assoc build-id #(resolve-fn compiled-file))
                                 (let [compile-fn (fn []
                                                    (if (contains? @active-builds build-id)
                                                      (println "Shadow-cljs watch active, skipping compile")
                                                      (do
                                                        (println "Compiling" filePath)
                                                        (compile [(name build-id)]))))]
                                   (if-not @ready
                                     (add-watch ready :compile (fn [& _args] (compile-fn)))
                                     (compile-fn))))))))))))))
