# Manual test procedure

End-to-end check of the plugin against a real standalone FitNesse. It takes about ten minutes.

Requirements: Maven, a JDK 17 or later (`java -version`), `curl`, and a browser. No internet access is needed to *display* the report (Tailwind CSS and JSZip are embedded in the jar), only to download FitNesse in step 2.

All commands are meant for a POSIX shell. Paths are built from variables, nothing is tied to a particular machine.

## 1. Build the plugin

From the root of this project:

```bash
mvn package
export PROJECT_DIR="$(pwd)"
export PLUGIN_JAR="$(ls "$PROJECT_DIR"/target/fitnesse-modern-report-*.jar | head -1)"
```

Expected: `BUILD SUCCESS` and the jar at `$PLUGIN_JAR`.

## 2. Set up a standalone FitNesse

The plugin registers itself (`META-INF/services`): copying the jar into `plugins/` is enough, no `plugins.properties` entry is needed.

```bash
export WORK_DIR="$HOME/fitnesse-test"      # any empty directory will do
mkdir -p "$WORK_DIR/plugins" && cd "$WORK_DIR"
curl -L -o fitnesse-standalone.jar \
  https://repo1.maven.org/maven2/org/fitnesse/fitnesse/20260313/fitnesse-20260313-standalone.jar
cp "$PLUGIN_JAR" plugins/
```

## 3. Start FitNesse (keep this terminal open)

```bash
java -jar fitnesse-standalone.jar -p 8089 -d "$PWD" -o
```

Expected: `Starting FitNesse on port: 8089`.

## 4. Create a test suite (in a second terminal)

```bash
cd "${WORK_DIR:-$HOME/fitnesse-test}"

# mk <page path> <wiki content> <page properties>
mk(){ mkdir -p "FitNesseRoot/$1"; printf '%s\n' "$2" > "FitNesseRoot/$1/content.txt"
printf '<?xml version="1.0"?>\n<properties>%s<Edit/><Properties/><Refactor/><Versions/><WhereUsed/><RecentChanges/><Files/><Search/></properties>\n' "$3" > "FitNesseRoot/$1/properties.xml"; }

# the suite page itself must NOT have the Test property, or FitNesse fails when it builds the XML results
mk SmokeSuite '!contents -R' '<Suite/>'

mk SmokeSuite/PassingTest '!define TEST_SYSTEM {slim}
!path fitnesse-standalone.jar

|import|
|fitnesse.slim.test|

|script|test slim|
|check|echo int|7|7|
|check|echo string|hello|hello|' '<Test/><Suites>smoke</Suites>'

mk SmokeSuite/FailingTest '!define TEST_SYSTEM {slim}
!path fitnesse-standalone.jar

|import|
|fitnesse.slim.test|

|script|test slim|
|check|echo int|7|8|' '<Test/><Suites>smoke</Suites>'
```

## 5. Check the suite in FitNesse alone

Open <http://127.0.0.1:8089/SmokeSuite?suite>: `PassingTest` is green and `FailingTest` is red.

## 6. Open the report

<http://127.0.0.1:8089/?responder=ExecutionReport&page=SmokeSuite>

Expected:

- four cards: success rate 50.0 %, 1 passed test, 1 anomaly;
- the table lists `FailingTest` (Fail, red) and `PassingTest` (Pass, green), both tagged `smoke`;
- *Details* on `FailingTest` opens the error panel with a single card, `check | echo int | 7 | [ 7 ] expected [ 8 ]`, and none of the surrounding wiki text;
- clicking a test name opens the FitNesse **execution page** of that run (not the wiki source);
- search, status filter, tag filter and column sorting work;
- the theme button switches between light and dark and the choice survives a reload;
- the Heap and CPU block reads *N/A — no metrics source configured* (see step 9).

## 7. Progress screen

The suite has to last a few seconds to see it. Add three tests that sleep for 5 seconds:

```bash
cd "${WORK_DIR:-$HOME/fitnesse-test}"
for n in SlowA SlowB SlowC; do
  mk "SmokeSuite/$n" '!define TEST_SYSTEM {slim}
!path fitnesse-standalone.jar

|import|
|fitnesse.slim.test|

|script|sleep|5000|' '<Test/><Suites>slow</Suites>'
done
```

(`mk` comes from step 4; define it again if you opened a new terminal.)

Open the report again. Expected:

- the page appears immediately, with a progress card showing the suite name, `0 / 5 tests (0%)` and a running timer;
- the bar advances as tests complete (`2 / 5` almost at once, then one more every five seconds), then the report replaces the progress card;
- do **not** reload the page while it runs: that starts a new run.

## 8. Error cases

<http://127.0.0.1:8089/?responder=ExecutionReport&page=Nope>

Expected: the progress card turns into *Unable to fetch execution results* with the cause (`HTTP 404`) and a **Retry** button. The HTTP status stays `200` because the response had already started; the error is part of the page.

Without a `page` parameter the report opens empty (KPIs show *N/A*) and no suite is run.

## 9. Infrastructure metrics (heap and CPU)

The plugin polls the application under test while the suite runs and draws the curves.

Create `plugins.properties` in the FitNesse directory (`$WORK_DIR`):

```properties
report.metrics.url=http://localhost:8080/actuator/metrics
report.metrics.interval=500
# report.metrics.format=actuator    (default: Spring Boot Actuator)
# report.metrics.format=prometheus  (Quarkus / Micrometer, with report.metrics.url=http://localhost:8080/q/metrics)
```

| Key | Meaning | Default |
|-----|---------|---------|
| `report.metrics.url` | Base URL of the application under test. Without it the charts show *N/A*. | *(disabled)* |
| `report.metrics.format` | `actuator` or `prometheus` | `actuator` |
| `report.metrics.interval` | Polling period in milliseconds (minimum 100) | `1000` |

Start the sample application, a dependency-free stand-in that exposes its own heap and CPU and generates a load wave (run it from the project root, or use `$PROJECT_DIR`):

```bash
java -Xmx512m "$PROJECT_DIR/sample-target-app/SampleTargetApp.java" 8080
```

Restart FitNesse (step 3), since `plugins.properties` is read at startup, then open the report. Expected: a *Java Heap* chart and a *CPU* chart with the peak annotated, and the subtitle *N samples every 500ms over Ns of execution*.

Stop the sample application and open the report again: it is still produced, and the block reads *N/A — metrics could not be read…* with the cause.

## 10. Rerun Failed and Export Zip

With `FailingTest` still failing, the *Rerun Failed (1)* button is active. Fix the test, then click the button:

```bash
cd "${WORK_DIR:-$HOME/fitnesse-test}"
sed -i.bak 's/|7|8|/|7|7|/' FitNesseRoot/SmokeSuite/FailingTest/content.txt
```

Expected: the button shows *Rerunning failed tests…*, then `FailingTest` turns to Pass, the cards read 100 %, the subtitle reads *last rerun: 1 re-executed, 1 now passing*, the other rows are untouched and the button is disabled.

Click *Export Zip*. Expected: a ZIP with `index.html`, `report.json` and, in `details/`, the execution page of every test. Unzip it and open `index.html` with the network disconnected: the report shows what was on screen when you exported it, and each test name opens its page in `details/`.

## 11. FitNesse authentication

The report is protected by FitNesse's own authentication and forwards the caller's credentials to the internal call-backs. Restart FitNesse with a user:

```bash
java -jar fitnesse-standalone.jar -p 8089 -d "$PWD" -a admin:secret -o
```

```bash
URL='http://127.0.0.1:8089/?responder=ExecutionReport&page=SmokeSuite'
curl -s -o /dev/null -w '%{http_code}\n' "$URL"                      # expected: 401
curl -s -o /dev/null -w '%{http_code}\n' -u admin:wrong "$URL"      # expected: 401
curl -s -o /dev/null -w '%{http_code}\n' -u admin:secret "$URL"     # expected: 200
```

Without `-a`, FitNesse is open and so is the report.

## Known limitations

- Every display of the report with a `page` parameter runs the suite again, and reloading it during a run starts another run.
- Progress relies on FitNesse's test history (enabled by default). If it is disabled the bar stays at its starting point while the timer keeps running.
- A reverse proxy that buffers responses delays the progress display.
- *Rerun Failed* re-executes only the failed tests (it uses FitNesse's `RerunLastFailures_<suite>` page); metrics are collected for the full run only.
- The plugin is compiled for Java 17: FitNesse has to run on Java 17 or later.

## Stop

Press Ctrl+C in the terminal running FitNesse (and the sample application), or run `pkill -f fitnesse-standalone.jar`.
