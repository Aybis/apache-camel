#!/usr/bin/env python3
"""Generates the provisioned Grafana dashboards in deploy/grafana/dashboards/.

Dashboards are code: edit this file and re-run it (python3 deploy/grafana/build_dashboards.py)
rather than editing the JSON or saving changes in the Grafana UI.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).parent / "dashboards"
PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
SVC = 'service="$service"'


class Layout:
    def __init__(self):
        self.y = 0
        self.x = 0
        self.row_h = 0
        self.next_id = 1

    def place(self, w, h):
        if self.x + w > 24:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        pos = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def row(self, title):
        if self.x:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        panel = {"type": "row", "title": title, "collapsed": False, "id": self.id(),
                 "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}, "panels": []}
        self.y += 1
        return panel

    def id(self):
        self.next_id += 1
        return self.next_id


def target(expr, legend="", ds=PROM, **extra):
    t = {"datasource": ds, "expr": expr, "refId": "A", "legendFormat": legend}
    t.update(extra)
    return t


def stat(L, title, expr, unit="short", w=4, thresholds=None, decimals=None, description=""):
    steps = thresholds or [{"color": "green", "value": None}]
    p = {"type": "stat", "title": title, "id": L.id(), "gridPos": L.place(w, 4), "datasource": PROM,
         "description": description,
         "targets": [target(expr, instant=True)],
         "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps},
                                      "color": {"mode": "thresholds"}}, "overrides": []},
         "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "area",
                     "textMode": "value"}}
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    return p


def ts(L, title, targets, unit="short", w=12, h=8, stack=False, ds=PROM, description=""):
    return {"type": "timeseries", "title": title, "id": L.id(), "gridPos": L.place(w, h), "datasource": ds,
            "description": description,
            "targets": [dict(t, refId=chr(65 + i)) for i, t in enumerate(targets)],
            "fieldConfig": {"defaults": {"unit": unit, "custom": {
                "drawStyle": "line", "lineWidth": 1, "fillOpacity": 15 if stack else 5,
                "stacking": {"mode": "normal" if stack else "none"}, "showPoints": "never"}},
                "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}


def logs(L, title, expr, w=24, h=12):
    return {"type": "logs", "title": title, "id": L.id(), "gridPos": L.place(w, h), "datasource": LOKI,
            "targets": [target(expr, ds=LOKI)],
            "options": {"showTime": True, "wrapLogMessage": True, "prettifyLogMessage": False,
                        "enableLogDetails": True, "sortOrder": "Descending", "dedupStrategy": "none"}}


def level_overrides(panel):
    colors = {"ERROR": "red", "WARN": "orange", "INFO": "green", "DEBUG": "blue", "TRACE": "purple"}
    panel["fieldConfig"]["overrides"] = [
        {"matcher": {"id": "byName", "options": lvl},
         "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}
        for lvl, c in colors.items()]
    return panel


def service_dashboard():
    L = Layout()
    p = []
    p.append(L.row("Health and throughput"))
    p.append(stat(L, "Instances up", f'sum(up{{{SVC}}})',
                  thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}]))
    p.append(stat(L, "Messages / s", f'sum(rate(camel_exchanges_total{{{SVC}, eventType="route"}}[$__rate_interval]))',
                  unit="reqps", decimals=2))
    p.append(stat(L, "Failure ratio", f'sum(rate(camel_exchanges_failed_total{{{SVC}, eventType="route"}}[$__rate_interval])) '
                  f'/ clamp_min(sum(rate(camel_exchanges_total{{{SVC}, eventType="route"}}[$__rate_interval])), 1e-9)',
                  unit="percentunit", decimals=1,
                  thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.01},
                              {"color": "red", "value": 0.05}],
                  description="Exchanges that ended failed (after redeliveries) / all exchanges."))
    p.append(stat(L, "In flight", f'sum(camel_exchanges_inflight{{{SVC}, routeId!=""}}) or vector(0)'))
    p.append(stat(L, "p95 processing", f'histogram_quantile(0.95, sum by (le) (rate(camel_route_policy_seconds_bucket{{{SVC}, routeId!=""}}[$__rate_interval])))',
                  unit="s", decimals=3))
    p.append(stat(L, "Errors logged (range)", f'sum(count_over_time({{{SVC}, level="ERROR"}}[$__range])) or vector(0)',
                  thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}]))
    p[-1]["datasource"] = LOKI
    p[-1]["targets"][0]["datasource"] = LOKI

    p.append(ts(L, "Messages / s by route", [target(
        f'sum by (routeId) (rate(camel_exchanges_total{{{SVC}, eventType="route", routeId=~"$route"}}[$__rate_interval]))', "{{routeId}}")],
        unit="reqps"))
    p.append(ts(L, "Failures and redeliveries / s", [
        target(f'sum by (routeId) (rate(camel_exchanges_failed_total{{{SVC}, eventType="route", routeId=~"$route"}}[$__rate_interval]))', "failed {{routeId}}"),
        target(f'sum by (routeId) (rate(camel_exchanges_external_redeliveries_total{{{SVC}, eventType="route", routeId=~"$route"}}[$__rate_interval]))', "redelivered {{routeId}}"),
        target(f'sum by (routeId) (rate(camel_exchanges_failures_handled_total{{{SVC}, eventType="route", routeId=~"$route"}}[$__rate_interval]))', "handled {{routeId}}")],
        unit="reqps"))
    p.append(ts(L, "Processing time by route (p50 / p95 / p99)", [
        target(f'histogram_quantile({q}, sum by (le, routeId) (rate(camel_route_policy_seconds_bucket{{{SVC}, routeId=~"$route", routeId!=""}}[$__rate_interval])))',
               f"p{int(q * 100)} {{{{routeId}}}}") for q in (0.5, 0.95, 0.99)], unit="s"))
    p.append(ts(L, "Running routes", [target(f'max by (instance) (camel_routes_running_routes{{{SVC}}})', "{{instance}}")]))

    p.append(L.row("Logs (Loki)"))
    p.append(level_overrides(ts(L, "Log lines by level", [target(
        f'sum by (level) (count_over_time({{{SVC}}}[$__auto]))', "{{level}}", ds=LOKI)],
        w=24, h=7, stack=True, ds=LOKI)))
    p.append(logs(L, "Errors and warnings", f'{{{SVC}, level=~"ERROR|WARN"}} |~ `(?i)$search`', h=10))
    p.append(logs(L, "All logs (filter with the Search box above)", f'{{{SVC}}} |~ `(?i)$search`', h=14))

    p.append(L.row("JVM"))
    p.append(ts(L, "Heap used", [target(f'sum by (instance) (jvm_memory_used_bytes{{{SVC}, area="heap"}})', "{{instance}}"),
                                 target(f'sum by (instance) (jvm_memory_max_bytes{{{SVC}, area="heap"}})', "max {{instance}}")],
                unit="bytes", w=8))
    p.append(ts(L, "CPU", [target(f'process_cpu_usage{{{SVC}}}', "{{instance}}")], unit="percentunit", w=8))
    p.append(ts(L, "GC pause / s", [target(f'sum by (instance) (rate(jvm_gc_pause_seconds_sum{{{SVC}}}[$__rate_interval]))', "{{instance}}")],
                unit="s", w=8))

    return {
        "uid": "camel-service", "title": "Camel service", "tags": ["camel", "platform"],
        "timezone": "browser", "schemaVersion": 41, "version": 1, "refresh": "30s",
        "time": {"from": "now-1h", "to": "now"},
        "templating": {"list": [
            {"name": "service", "label": "Service", "type": "query", "datasource": PROM,
             "query": {"query": "label_values(camel_exchanges_total, service)", "refId": "service"},
             "definition": "label_values(camel_exchanges_total, service)", "refresh": 2, "sort": 1,
             "current": {}, "includeAll": False, "multi": False},
            {"name": "route", "label": "Route", "type": "query", "datasource": PROM,
             "query": {"query": f'label_values(camel_exchanges_total{{{SVC}, eventType="route"}}, routeId)', "refId": "route"},
             "definition": f'label_values(camel_exchanges_total{{{SVC}, eventType="route"}}, routeId)', "refresh": 2,
             "includeAll": True, "allValue": ".*", "multi": True, "current": {"text": "All", "value": "$__all"}},
            {"name": "search", "label": "Search logs", "type": "textbox", "query": "", "current": {"text": "", "value": ""}},
        ]},
        "links": [{"title": "All services", "type": "link", "url": "/d/camel-overview"}],
        "panels": p,
    }


def overview_dashboard():
    L = Layout()
    p = []
    p.append(stat(L, "Services up", 'count(sum by (service) (up{service!="console"}) > 0)', w=6))
    p.append(stat(L, "Services down", 'count(sum by (service) (up{service!="console"}) == 0) or vector(0)', w=6,
                  thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}]))
    p.append(stat(L, "Messages / s (all)", 'sum(rate(camel_exchanges_total{eventType="route"}[$__rate_interval]))', unit="reqps", w=6, decimals=2))
    p.append(stat(L, "Failed / s (all)", 'sum(rate(camel_exchanges_failed_total{eventType="route"}[$__rate_interval]))', unit="reqps", w=6, decimals=3,
                  thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 0.001}]))

    table = {"type": "table", "title": "Services (click a name to drill down)", "id": L.id(), "gridPos": L.place(24, 10),
             "datasource": PROM,
             "targets": [
                 dict(target('sum by (service, domain) (up{service!="console"})', format="table", instant=True), refId="A"),
                 dict(target('sum by (service) (rate(camel_exchanges_total{eventType="route"}[5m]))', format="table", instant=True), refId="B"),
                 dict(target('sum by (service) (rate(camel_exchanges_failed_total{eventType="route"}[5m]))', format="table", instant=True), refId="C"),
                 dict(target('histogram_quantile(0.95, sum by (le, service) (rate(camel_route_policy_seconds_bucket[5m])))', format="table", instant=True), refId="D"),
             ],
             "transformations": [
                 {"id": "joinByField", "options": {"byField": "service", "mode": "outer"}},
                 {"id": "organize", "options": {
                     "excludeByName": {"Time": True, "Time 1": True, "Time 2": True, "Time 3": True, "Time 4": True},
                     "renameByName": {"Value #A": "Instances up", "Value #B": "Messages / s",
                                      "Value #C": "Failed / s", "Value #D": "p95 (s)", "service": "Service",
                                      "domain": "Domain"}}}],
             "fieldConfig": {"defaults": {"decimals": 3}, "overrides": [
                 {"matcher": {"id": "byName", "options": "Service"}, "properties": [{"id": "links", "value": [
                     {"title": "Open", "url": "/d/camel-service?var-service=${__value.raw}&${__url_time_range}"}]}]},
                 {"matcher": {"id": "byName", "options": "Instances up"}, "properties": [
                     {"id": "decimals", "value": 0},
                     {"id": "custom.cellOptions", "value": {"type": "color-background"}},
                     {"id": "thresholds", "value": {"mode": "absolute", "steps": [
                         {"color": "red", "value": None}, {"color": "green", "value": 1}]}}]}]},
             "options": {"showHeader": True, "sortBy": [{"displayName": "Failed / s", "desc": True}]}}
    p.append(table)
    p.append(ts(L, "Messages / s by service", [target('sum by (service) (rate(camel_exchanges_total{eventType="route"}[$__rate_interval]))', "{{service}}")], unit="reqps"))
    p.append(ts(L, "Errors logged by service", [target('sum by (service) (count_over_time({level="ERROR"}[$__auto]))', "{{service}}", ds=LOKI)], ds=LOKI))
    p.append(logs(L, "Latest errors across all services", '{level="ERROR"}', h=12))
    return {
        "uid": "camel-overview", "title": "Camel platform overview", "tags": ["camel", "platform"],
        "timezone": "browser", "schemaVersion": 41, "version": 1, "refresh": "30s",
        "time": {"from": "now-1h", "to": "now"}, "templating": {"list": []}, "panels": p,
    }


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    for name, dash in (("camel-service.json", service_dashboard()), ("camel-overview.json", overview_dashboard())):
        (OUT / name).write_text(json.dumps(dash, indent=2) + "\n")
        print("wrote", OUT / name)
