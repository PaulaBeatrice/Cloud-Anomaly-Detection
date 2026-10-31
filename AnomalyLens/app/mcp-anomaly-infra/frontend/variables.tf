variable "aws_region" {
  type        = string
  description = "AWS region for the S3 bucket."
  default     = "eu-central-1"
}

variable "bucket_name" {
  type        = string
  description = "Globally unique bucket name for the frontend UI."
}

variable "dist_dir" {
  type        = string
  description = "Local path to the build output directory (Vite dist/)."
  default     = "../../mcp-anomaly-ui/dist"
}

variable "project_tag" {
  type    = string
  default = "mcp-anomaly-ui"
}

variable "enable_invalidation" {
  type        = bool
  description = "Whether to create a CloudFront invalidation after uploading dist/."
  default     = true
}

variable "backend_origin_domain" {
  type        = string
  description = "Public DNS name of the EC2 backend, without http:// or port."
}

variable "backend_origin_http_port" {
  type        = number
  description = "HTTP port used by the backend service on EC2."
  default     = 8080
}