# Without a domain: CloudFront serves HTTPS on its own https://<id>.cloudfront.net URL and reaches the internal
# load balancer through a VPC origin, so the load balancer has no public address at all.

resource "aws_cloudfront_vpc_origin" "keyra" {
  count = local.use_domain ? 0 : 1

  vpc_origin_endpoint_config {
    name                   = var.name
    arn                    = aws_lb.keyra.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# CloudFront creates this group in the VPC once the VPC origin exists (AWS documents it as a naming pattern, so
# match by prefix). The load balancer admits only it: stricter than CloudFront's whole origin-facing prefix list.
data "aws_security_group" "cloudfront_vpc_origin" {
  count = local.use_domain ? 0 : 1

  vpc_id = local.vpc_id
  filter {
    name   = "group-name"
    values = ["CloudFront-VPCOrigins-Service-SG*"]
  }

  depends_on = [aws_cloudfront_vpc_origin.keyra]
}

# CloudFront drops Authorization from GET requests unless it is in the cache key, so it is; with a default TTL of 0
# and Keyra's no-store headers, nothing is cached.
resource "aws_cloudfront_cache_policy" "keyra" {
  count = local.use_domain ? 0 : 1

  name        = "${var.name}-api"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 1

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true

    headers_config {
      header_behavior = "whitelist"
      headers {
        items = ["Authorization"]
      }
    }
    cookies_config {
      cookie_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "all"
    }
  }
}

locals {
  # AWS managed origin request policy: every viewer header (except Host), cookie and query string.
  all_viewer_except_host = "b689b0a8-53d0-40ab-baf2-68738e2966ac"
}

resource "aws_cloudfront_distribution" "keyra" {
  count = local.use_domain ? 0 : 1

  enabled         = true
  comment         = "Keyra (${var.name})"
  is_ipv6_enabled = true
  http_version    = "http2and3"

  origin {
    origin_id   = "keyra"
    domain_name = aws_lb.keyra.dns_name

    vpc_origin_config {
      vpc_origin_id = aws_cloudfront_vpc_origin.keyra[0].id
    }
  }

  default_cache_behavior {
    target_origin_id         = "keyra"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = aws_cloudfront_cache_policy.keyra[0].id
    origin_request_policy_id = local.all_viewer_except_host
    compress                 = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # The *.cloudfront.net certificate always allows TLS 1.0+ (AWS ignores minimum_protocol_version for it);
  # use domain_name for a TLS 1.2+ minimum.
  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
