variable "aws_region" {
  type    = string
  default = "eu-central-1"
}

variable "bucket_name" {
  type        = string
  description = "S3 bucket name (must be globally unique)"
}