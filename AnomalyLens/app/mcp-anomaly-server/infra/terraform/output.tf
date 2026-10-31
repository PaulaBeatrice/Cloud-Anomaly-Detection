output "bucket_name" {
  value = aws_s3_bucket.telemetry.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.telemetry.arn
}

output "region" {
  value = var.aws_region
}
