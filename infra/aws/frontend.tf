resource "aws_acm_certificate" "web" {
  provider                  = aws.edge
  domain_name               = var.root_domain
  subject_alternative_names = ["admin.${var.root_domain}"]
  validation_method         = "DNS"
  lifecycle { create_before_destroy = true }
}
resource "aws_route53_record" "web_validation" {
  for_each = { for option in aws_acm_certificate.web.domain_validation_options : option.domain_name => option }
  zone_id  = var.hosted_zone_id
  name     = each.value.resource_record_name
  type     = each.value.resource_record_type
  records  = [each.value.resource_record_value]
  ttl      = 60
}
resource "aws_acm_certificate_validation" "web" {
  provider                = aws.edge
  certificate_arn         = aws_acm_certificate.web.arn
  validation_record_fqdns = [for record in aws_route53_record.web_validation : record.fqdn]
}
resource "aws_s3_bucket" "web" {
  for_each = toset(["customer", "admin"])
  bucket   = "${var.project}-${each.key}-${data.aws_caller_identity.current.account_id}"
}
resource "aws_s3_bucket_versioning" "web" {
  for_each = aws_s3_bucket.web
  bucket   = each.value.id
  versioning_configuration { status = "Enabled" }
}
resource "aws_s3_bucket_public_access_block" "web" {
  for_each                = aws_s3_bucket.web
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_cloudfront_origin_access_control" "web" {
  name                              = var.project
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
resource "aws_cloudfront_response_headers_policy" "web" {
  name = var.project
  security_headers_config {
    content_type_options { override = true }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "no-referrer"
      override        = true
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      override                   = true
    }
  }
}
resource "aws_cloudfront_distribution" "web" {
  for_each            = aws_s3_bucket.web
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = [each.key == "customer" ? var.root_domain : "admin.${var.root_domain}"]
  origin {
    domain_name              = each.value.bucket_regional_domain_name
    origin_id                = "app"
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }
  default_cache_behavior {
    target_origin_id           = "app"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    min_ttl                    = 0
    default_ttl                = 300
    max_ttl                    = 86400
    response_headers_policy_id = aws_cloudfront_response_headers_policy.web.id
    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }
  }
  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }
  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }
  restrictions {
    geo_restriction { restriction_type = "none" }
  }
  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.web.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
resource "aws_s3_bucket_policy" "web" {
  for_each = aws_s3_bucket.web
  bucket   = each.value.id
  policy   = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "cloudfront.amazonaws.com" }, Action = "s3:GetObject", Resource = "${each.value.arn}/*", Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.web[each.key].arn } } }] })
}
resource "aws_route53_record" "web" {
  for_each = aws_cloudfront_distribution.web
  zone_id  = var.hosted_zone_id
  name     = each.key == "customer" ? var.root_domain : "admin.${var.root_domain}"
  type     = "A"
  alias {
    name                   = each.value.domain_name
    zone_id                = each.value.hosted_zone_id
    evaluate_target_health = false
  }
}

