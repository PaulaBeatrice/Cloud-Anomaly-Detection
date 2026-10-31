output "bucket_name" {
  value = aws_s3_bucket.ui.bucket
}

output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.ui.domain_name
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.ui.id
}