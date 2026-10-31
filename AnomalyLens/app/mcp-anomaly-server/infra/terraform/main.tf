provider "aws" {
  region = var.aws_region
}

resource "aws_s3_bucket" "telemetry" {
  bucket = var.bucket_name

  tags = {
    Project   = "mcp-anomaly-server"
    ManagedBy = "terraform"
  }
}

resource "aws_s3_bucket_public_access_block" "telemetry" {
  bucket                  = aws_s3_bucket.telemetry.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "telemetry" {
  bucket = aws_s3_bucket.telemetry.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "telemetry" {
  bucket = aws_s3_bucket.telemetry.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_object" "incoming_prefix" {
  bucket  = aws_s3_bucket.telemetry.id
  key     = "incoming/"
  content = ""
}

resource "aws_s3_object" "scored_prefix" {
  bucket  = aws_s3_bucket.telemetry.id
  key     = "scored/"
  content = ""
}
