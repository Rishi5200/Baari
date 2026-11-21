"""Writes ops/grafana/dashboards/baari.json (PLAN Phase 8). Run: python ops/grafana/make_dashboard.py

Kept as a script so the panels stay consistent; the JSON it writes is what Grafana provisions.
"""
import json
import pathlib

DS = {"type": "prometheus", "uid": "prometheus"}


def panel(pid, title, exprs, unit, x, y, w=12, h=8, kind="timeseries", description=""):
    return {
        "id": pid,
        "type": kind,
        "title": title,
        "description": description,
        "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}} if kind == "timeseries" else {},
        "targets": [
            {"refId": chr(65 + i), "datasource": DS, "expr": e, "legendFormat": legend}
            for i, (e, legend) in enumerate(exprs)
        ],
    }


panels = [
    panel(1, "Tokens per hour", [("sum by (source) (increase(baari_tokens_issued_total[1h]))", "{{source}}")],
          "short", 0, 0, description="Tokens issued in the last hour, by QR or reception."),
    panel(2, "Actual wait (p50, p90)", [
        ("histogram_quantile(0.5, sum by (le) (rate(baari_wait_actual_seconds_bucket[30m])))", "p50"),
        ("histogram_quantile(0.9, sum by (le) (rate(baari_wait_actual_seconds_bucket[30m])))", "p90"),
    ], "s", 12, 0, description="From joining to the start of the consultation."),
    panel(3, "ETA mean absolute error by estimator", [(
        "sum by (estimator) (rate(baari_eta_abs_error_seconds_sum[1h]))"
        " / sum by (estimator) (rate(baari_eta_abs_error_seconds_count[1h]))", "{{estimator}}")],
          "s", 0, 8, description="Shown estimate versus the wait that followed (PLAN 8.7)."),
    panel(4, "Actual wait inside the shown range", [(
        "sum(rate(baari_eta_in_range_total{hit=\"true\"}[1h])) / sum(rate(baari_eta_in_range_total[1h]))",
        "inside range")], "percentunit", 12, 8, description="Target 60 to 70% (PLAN 8.5)."),
    panel(5, "Outbox lag", [("max(baari_outbox_lag_seconds)", "oldest unsent event")], "s", 0, 16, w=8,
          description="Age of the oldest event not yet sent to Kafka (ADR 0011)."),
    panel(6, "Live SSE connections", [("sum(baari_sse_connections)", "connections")], "short", 8, 16, w=8),
    panel(7, "Join latency p95", [(
        "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket"
        "{method=\"POST\", uri=~\"/j/.*|/api/public/queues/.*/tokens\"}[5m])))", "p95")], "s", 16, 16, w=8,
          description="Phase 8 target: under 300 ms."),
    panel(8, "Notification failures", [(
        "sum by (kind, channel) (increase(baari_notifications_total{outcome=\"failed\"}[1h]))", "{{kind}} {{channel}}")],
          "short", 0, 24, w=8),
    panel(9, "Dead-letter topic", [("sum(increase(baari_events_dlt_total[1h]))", "messages")], "short", 8, 24, w=8,
          description="Events that failed every retry (ADR 0011)."),
    panel(10, "Rate-limited requests", [("sum by (rule) (increase(baari_rate_limited_total[1h]))", "{{rule}}")],
          "short", 16, 24, w=8, description="429 answers per rule (ADR 0014)."),
]

dashboard = {
    "uid": "baari-overview",
    "title": "Baari overview",
    "tags": ["baari"],
    "timezone": "browser",
    "schemaVersion": 39,
    "version": 1,
    "refresh": "30s",
    "time": {"from": "now-6h", "to": "now"},
    "panels": panels,
}

out = pathlib.Path(__file__).parent / "dashboards" / "baari.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n", encoding="utf-8")
print("wrote", out)
