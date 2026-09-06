# An uptime check on the public health endpoint, which reports DOWN when the database is unreachable, and
# an email alert on it. Both exist only when alert_email is set. Free at this scale.
locals {
  monitoring   = var.alert_email == "" ? 0 : 1
  service_host = regex("^https://([^/:]+)", var.base_url)[0]
}

resource "google_monitoring_notification_channel" "email" {
  count        = local.monitoring
  display_name = "campus-coffee operator"
  type         = "email"

  labels = {
    email_address = var.alert_email
  }

  depends_on = [google_project_service.required]
}

resource "google_monitoring_uptime_check_config" "health" {
  count        = local.monitoring
  display_name = "campus-coffee /actuator/health"
  timeout      = "10s"
  period       = "300s"

  http_check {
    path         = "/actuator/health"
    port         = 443
    use_ssl      = true
    validate_ssl = true

    accepted_response_status_codes {
      status_class = "STATUS_CLASS_2XX"
    }
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project
      host       = local.service_host
    }
  }

  content_matchers {
    content = "\"status\":\"UP\""
    matcher = "CONTAINS_STRING"
  }

  depends_on = [google_project_service.required]
}

resource "google_monitoring_alert_policy" "health" {
  count        = local.monitoring
  display_name = "campus-coffee health check failing"
  combiner     = "OR"

  conditions {
    display_name = "/actuator/health uptime check fails"

    condition_threshold {
      filter          = "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND resource.type=\"uptime_url\" AND metric.label.check_id=\"${google_monitoring_uptime_check_config.health[0].uptime_check_id}\""
      comparison      = "COMPARISON_GT"
      threshold_value = 1
      duration        = "300s"

      aggregations {
        alignment_period     = "1200s"
        per_series_aligner   = "ALIGN_NEXT_OLDER"
        cross_series_reducer = "REDUCE_COUNT_FALSE"
        group_by_fields      = ["resource.label.*"]
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.email[0].id]
}
