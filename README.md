# cypress-clojurescript-preprocessor

A [Cypress preprocessor](https://docs.cypress.io/api/plugins/preprocessors-api.html) for [ClojureScript](https://clojurescript.org/), which uses [Shadow CLJS](https://github.com/thheller/shadow-cljs) for processing Cypress tests written in ClojureScript.

The plugin works by inspecting the Cypress `integrationFolder` (by default, `cypress/integration`) for ClojureScript files (`*.cljs`, e.g. `cypress/integration/my_app/app_test.cljs` -> `(ns my-app.app-test)`) and generates a `shadow-cljs.edn` configuration file with a build for each test file. This configuration is then used to compile the tests into Javascript before submitting to the browser.

The shadow-cljs server is kept running while the Cypress runner is active. When a test is run, the test file is watched for changes and a recompile is done if the test file is changed and Cypress is notified to rerun the test. The first compile is a bit slow, but subsequent compiles are fast (the plugin uses shadow-cljs `compile` command, which defaults to [`optimizations: none`](https://shadow-cljs.github.io/docs/UsersGuide.html#Optimization)).

## Example usage

1. Create a project

   ```sh
   $ mkdir cypress-cljs-sample
   $ cd cypress-cljs-sample
   $ echo {} > package.json
   ```

2. Install [Cypress](https://docs.cypress.io/guides/getting-started/installing-cypress.html#Installing)

   ```sh
   $ npm install cypress --save-dev
   ```

3. Install cypress-clojurescript-preprocessor

   ```sh
   $ npm install cypress-clojurescript-preprocessor
   ```

4. Configure ClojureScript preprocessor

   ```sh
   $ mkdir -p cypress/plugins
   $ cat << EOF > cypress/plugins/index.js
   const makeCljsPreprocessor = require('cypress-clojurescript-preprocessor');
   /**
    * @type {Cypress.PluginConfig}
    */
   module.exports = (on, config) => {
     on('file:preprocessor', makeCljsPreprocessor(config));
   };
   EOF
   ```

   This will delegate files other than `*.cljs` to the [default Browserify preprocessor](https://docs.cypress.io/api/plugins/preprocessors-api.html#Defaults). If you need to run other preprocessors, then combine them with for example:

   ```js
   module.exports = (on, config) => {
     const browserifyPreprocessor = makeBrowserifyPreprocessor();
     const cljsPreprocessor = makeCljsPreprocessor(config);
     // Use the default Browserify preprocessor for files other than *.cljs
     on('file:preprocessor', (file) => file.filePath.endsWith('.cljs') ? cljsPreprocessor(file) : browserifyPreprocessor(file));
   };
   ```
5. Write test in ClojureScript

   ```sh
   $ mkdir -p cypress/integration/examples
   $ cat << EOF > cypress/integration/examples/window.cljs
   (ns examples.window
     (:require-macros [latte.core :refer [describe beforeEach it]]))

   (def cy js/cy)

   (describe "Window"
     (beforeEach []
       (.visit cy "https://example.cypress.io/commands/window"))
     (it "cy.window() - get the global window object" []
       (.should (.window cy) "have.property" "top")))
   EOF
   ```
6. Run test

   ```sh
   $ ./node_modules/.bin/cypress open
   ```

## REPL in Cypress

You can active REPL into a test in the browser that Cypress is controlling.

1. Lookup NREPL port used by the shadow-cljs server

   ```
   $ cat .preprocessor-cljs/.shadow-cljs/nrepl.port
   50796
   ```

2. Connect to the NREPL server and start shadow-cljs watch

   ```
   shadow.user> (shadow/watch :window)
   ```

   The build-id is a keyword of the test file name

3. Start a ClojureScript repl

   ```
   shadow.user> (shadow/repl :window)
   To quit, type: :cljs/quit
   [:selected :window]
   cljs.user> (js/alert "plop") ;; To try out that it works
   ```

Now you can use the slightly undocumented `now` command to execute Cypress command immediately, for example, to select an option:

```
cljs.user> (-> (.now js/cy "get" "#some-opt")
               (.then (fn [el]
                        (.now js/cy "select" el "two"))))
#object[Promise [object Promise]]
```

Some references to the `now` command:

* https://github.com/cypress-io/cypress/issues/6080#issuecomment-570481923
* https://docs.cypress.io/guides/guides/debugging#Run-Cypress-command-outside-the-test
* https://github.com/cypress-io/cypress/issues/8195
* https://github.com/cypress-io/cypress/issues/3636#issuecomment-511315778

## Configuration

### Shadow CLJS configuration override

The Shadow CLJS configuration used by the preprocessor may be overridden via a `shadow-cljs-override.edn` file, which is merged on top of the default configuration with [meta-merge](https://github.com/weavejester/meta-merge). By default, `[mocha-latte "0.1.2"]` and `[chai-latte "0.2.0"]` are included in the shadow-cljs.edn configuration used by the preprocessor.

### Shared namespaces

The working directory of the shadow-cljs compiler that is driven by the preprocessor is the `cypress` folder. Because of this, in order to include shared code that is in a folder at the same level as the `cypress` directory, use a relative (or absolute path) e.g.

```
$ tree -I "node_modules" -P common.cljs\|window.cljs
.
├── cypress
│   ├── fixtures
│   ├── integration
│   │   └── examples
│   │       └── window.cljs
│   ├── plugins
│   └── support
└── src ;; shared code directory
    └── net
        └── tiuhti
            └── common.cljs
$ cat shadow-cljs-override.edn
{:source-paths ["../src"]} ;; path relative to `cypress` directory
$ head -3 cypress/integration/examples/window.cljs
(ns examples.window
  (:require-macros [latte.core :refer [describe beforeEach it]])
  (:require [net.tiuhti.common :as common]))  ;; require namespace in the `src` directory
```

Dependencies used by the shared code would have to be specified in the `:dependencies` key in `shadow-cljs-override.edn` file.

## Development

Here's some notes on how to develop this library.

### Shadow-cljs repl

The preprocessor is written in ClojureScript and compiled to a npm library with the help of shadow-cljs. To develop the library in a ClojureScript repl:

1. Start node repl via `shadow-cljs node-repl`
2. Connect to the `nrepl` server for example from Emacs with cider: `cider-connect`
3. In `nrepl` at the `shadow.user>` prompt, switch to the node repl via: ` (shadow/repl :node-repl)`

### Use locally compiled version

To use a local version of the preprocessor, point the client project to the local clone of the preprocessor:

```0% cat package.json
{
  "devDependencies": {
    "cypress": "^7.6.0",
    "shadow-cljs": "^2.14.5"      ;; <-- Also, need to have shadow-cljs installed in the client project
  },
  "dependencies": {
    "cypress-clojurescript-preprocessor": "../cypress-clojurescript-preprocessor"  ;;  <-- Directory of the local copy
  }
}
```

Note the the client project also needs shadow-cljs to be installed (apparently :)).

Then, compile your changes via

```
$ npm run prepare
```

And (re)start Cypress in the client project:

```
$ ./node_modules/.bin/cypress open
```

## Changelog

* 0.1.7 
  * Bump shadow-cljs to 2.14.5
  * Call process/exit to not leave zombies when Cypress exits
  * When adding a new test while the preprocessor is running
    * Use same optimization level as for initial build config
    * Prevent duplicate merge of `shadow-cljs-override.edn`
* 0.1.6 Support namespaces with multiple segments (thanks @martinklepsch !), also files with underscore. Bump chokidar to 3.5.2
* 0.1.5 Merge override config ontop final config, allows to add additional :source-paths
* 0.1.4 Bundle browserify preprocessor
* 0.1.3 Add shadow-cljs-override.edn
* 0.1.2 Bundle Bundle mocha-latte and chai-latte
* 0.1.0 Initial release
