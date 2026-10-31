provider "aws" {
  region = var.aws_region
}

locals {
  origin_id         = "s3-ui-origin"
  backend_origin_id = "backend-ec2-origin"
  dist_files       = fileset(var.dist_dir, "**")

  mime_types = {
    "html"  = "text/html"
    "css"   = "text/css"
    "js"    = "application/javascript"
    "mjs"   = "application/javascript"
    "json"  = "application/json"
    "svg"   = "image/svg+xml"
    "png"   = "image/png"
    "jpg"   = "image/jpeg"
    "jpeg"  = "image/jpeg"
    "gif"   = "image/gif"
    "ico"   = "image/x-icon"
    "txt"   = "text/plain"
    "map"   = "application/json"
    "woff"  = "font/woff"
    "woff2" = "font/woff2"
  }
}

# -------------------------
# S3 bucket (private)
# -------------------------

resource "random_id" "suffix" {
  byte_length = 3
}

resource "aws_s3_bucket" "ui" {
  bucket = "${var.bucket_name}-${random_id.suffix.hex}"
  tags = { Project = var.project_tag }
}



resource "aws_s3_bucket_public_access_block" "ui" {
  bucket                  = aws_s3_bucket.ui.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "ui" {
  bucket = aws_s3_bucket.ui.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ui" {
  bucket = aws_s3_bucket.ui.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# -------------------------
# CloudFront Origin Access Control (OAC) - recommended for S3 origins
# -------------------------
resource "aws_cloudfront_origin_access_control" "ui_oac" {
  name                              = "${var.project_tag}-oac"
  description                       = "OAC for CloudFront to access private S3 origin"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
# AWS recommends OAC for securing S3 origins (vs OAI). [1](https://www.coretechnologies.com/products/AlwaysUp/Apps/OllamaWindowsService.html)[2](https://www.mykolaaleksandrov.dev/posts/2025/07/ollama-network-setup/)[3](https://gist.github.com/karthiks/fb89ea8230e6574200c8644d9ac878b4)

# -------------------------
# CloudFront Distribution
# -------------------------
resource "aws_cloudfront_distribution" "ui" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "UI CDN for ${var.project_tag}"
  default_root_object = "index.html"

  origin {
    domain_name              = aws_s3_bucket.ui.bucket_regional_domain_name
    origin_id                = local.origin_id
    origin_access_control_id = aws_cloudfront_origin_access_control.ui_oac.id
  }

  origin {
    domain_name = var.backend_origin_domain
    origin_id   = local.backend_origin_id

    custom_origin_config {
      http_port              = var.backend_origin_http_port
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = local.origin_id
    viewer_protocol_policy = "redirect-to-https"

    allowed_methods = ["GET", "HEAD"]
    cached_methods  = ["GET", "HEAD"]

    compress = true

    forwarded_values {
      query_string = false
      cookies {
        forward = "none"
      }
    }

    min_ttl     = 0
    default_ttl = 3600
    max_ttl     = 86400
  }

  ordered_cache_behavior {
    path_pattern           = "/api/*"
    target_origin_id       = local.backend_origin_id
    viewer_protocol_policy = "redirect-to-https"

    allowed_methods = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods  = ["GET", "HEAD", "OPTIONS"]

    compress = true

    forwarded_values {
      query_string = true

      headers = [
        "Origin",
        "Access-Control-Request-Method",
        "Access-Control-Request-Headers",
        "Content-Type",
        "Authorization"
      ]

      cookies {
        forward = "none"
      }
    }

    min_ttl     = 0
    default_ttl = 0
    max_ttl     = 0
  }

  # SPA routing:
  # When using OAC, S3 often returns 403 for missing objects; map 403/404 to /index.html.
  # CloudFront supports custom error responses. [4](https://www.glukhov.org/llm-hosting/ollama/ollama-cheatsheet/)[5](https://deepwiki.com/ollama/ollama/1.3-command-line-interface)
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
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = {
    Project = var.project_tag
  }
}

# -------------------------
# Bucket policy: allow CloudFront (service principal) to GetObject, restricted by distribution ARN
# This is the standard pattern for OAC with S3 origins. [1](https://www.coretechnologies.com/products/AlwaysUp/Apps/OllamaWindowsService.html)[2](https://www.mykolaaleksandrov.dev/posts/2025/07/ollama-network-setup/)
# -------------------------
data "aws_iam_policy_document" "ui_bucket_policy" {
  statement {
    sid     = "AllowCloudFrontRead"
    effect  = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.ui.arn}/*"]

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.ui.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "ui" {
  bucket = aws_s3_bucket.ui.id
  policy = data.aws_iam_policy_document.ui_bucket_policy.json
}

# -------------------------
# Upload dist/ to S3 with Terraform: fileset + for_each + aws_s3_object
# This pattern is commonly used to upload multiple files. [6](https://repost.aws/questions/QUXSyHftdhTF-fwWTQwFZCcw/using-bedrock-invokemodel-api-with-prompt-created-in-prompt-management)[7](https://www.youtube.com/watch?v=cd_2NXuTGlQ)
# -------------------------
resource "aws_s3_object" "dist" {
  for_each = local.dist_files

  bucket = aws_s3_bucket.ui.id
  key    = each.value
  source = "${var.dist_dir}/${each.value}"

  # Re-upload when file content changes
  etag = filemd5("${var.dist_dir}/${each.value}")

  # Simple content-type mapping based on extension
  content_type = lookup(
    local.mime_types,
    element(split(".", each.value), length(split(".", each.value)) - 1),
    "application/octet-stream"
  )
}