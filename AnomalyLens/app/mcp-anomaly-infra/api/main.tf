provider "aws" {
  region = var.aws_region
}

locals {
  origin_id = "ec2-api-origin"
}

resource "aws_cloudfront_distribution" "api" {
  enabled         = true
  is_ipv6_enabled = true
  comment         = "API CDN for MCP backend"
  price_class     = "PriceClass_All"

  origin {
    domain_name = var.backend_origin_domain
    origin_id   = local.origin_id

    custom_origin_config {
      http_port              = 8080
      https_port             = 443
      origin_protocol_policy = "http-only" 
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = local.origin_id
    viewer_protocol_policy = "redirect-to-https"

    allowed_methods = ["GET","HEAD","OPTIONS","PUT","POST","PATCH","DELETE"]
    cached_methods  = ["GET","HEAD"]

    compress = true

    forwarded_values {
      query_string = true
      headers      = ["Origin","Content-Type"]
      cookies {
        forward = "none"
      }
    }

    min_ttl     = 0
    default_ttl = 0
    max_ttl     = 0
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = { Project = var.project_tag }
}