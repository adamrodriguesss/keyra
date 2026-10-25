# HTTPS on your domain: an ACM certificate validated in your Route 53 zone, in front of the tasks.
# Without a domain, cloudfront.tf terminates HTTPS instead.
resource "aws_acm_certificate" "keyra" {
  count = local.use_domain ? 1 : 0

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# One domain, so exactly one validation record (a for_each over the options can't be planned before the certificate exists).
locals {
  cert_validation = local.use_domain ? one(aws_acm_certificate.keyra[0].domain_validation_options) : null
}

resource "aws_route53_record" "validation" {
  count = local.use_domain ? 1 : 0

  zone_id         = var.route53_zone_id
  name            = local.cert_validation.resource_record_name
  type            = local.cert_validation.resource_record_type
  records         = [local.cert_validation.resource_record_value]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "keyra" {
  count = local.use_domain ? 1 : 0

  certificate_arn         = aws_acm_certificate.keyra[0].arn
  validation_record_fqdns = [aws_route53_record.validation[0].fqdn]
}

resource "aws_lb" "keyra" {
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = !local.use_domain
  subnets                    = local.alb_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
}

# Only ready tasks get traffic: readiness includes the database connection.
resource "aws_lb_target_group" "keyra" {
  name                 = var.name
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = local.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  count = local.use_domain ? 1 : 0

  load_balancer_arn = aws_lb.keyra.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.keyra[0].certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.keyra.arn
  }
}

# With a domain, plain HTTP only redirects. Without, it is how CloudFront reaches the tasks, inside the VPC.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.keyra.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = local.use_domain ? "redirect" : "forward"
    target_group_arn = local.use_domain ? null : aws_lb_target_group.keyra.arn

    dynamic "redirect" {
      for_each = local.use_domain ? [1] : []
      content {
        protocol    = "HTTPS"
        port        = "443"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_route53_record" "keyra" {
  count = local.use_domain ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_lb.keyra.dns_name
    zone_id                = aws_lb.keyra.zone_id
    evaluate_target_health = true
  }
}
