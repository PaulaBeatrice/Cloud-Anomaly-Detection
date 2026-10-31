variable "aws_region" {
  type        = string
  description = "AWS region"
  default     = "eu-central-1"
}

variable "instance_type" {
  type        = string
  description = "EC2 instance type"
  default     = "t3.micro"
}

variable "telemetry_bucket" {
  type        = string
  description = "Existing S3 bucket used for incoming/ and scored/"
}

variable "app_bundle_s3_key" {
  type        = string
  description = "S3 key for backend bundle (tar.gz) that contains quarkus jar + scorer + model"
  default     = "deploy/backend_bundle.tar.gz"
}

variable "ui_origin" {
  type        = string
  description = "Full UI origin like https://d2scjv2qknufnr.cloudfront.net"
}

variable "admin_cidr" {
  type        = string
  description = "Your public IP in CIDR notation for SSH access, e.g. 86.120.34.56/32"
}