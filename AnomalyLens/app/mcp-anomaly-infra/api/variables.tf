variable "aws_region" { type = string, default = "eu-central-1" }

variable "backend_origin_domain" {
  type        = string
  description = "EC2 public DNS"
}

variable "project_tag" { type = string, default = "mcp-anomaly-api" }