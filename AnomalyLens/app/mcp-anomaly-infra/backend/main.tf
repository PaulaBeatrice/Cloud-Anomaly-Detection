provider "aws" {
  region = var.aws_region
}

# Latest Amazon Linux 2023 AMI
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64*"]
  }
}

resource "aws_security_group" "backend_sg" {
  name        = "mcp-backend-sg"
  description = "Allow SSH and backend HTTP"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  # Backend listens on 8080; CloudFront will call it as origin
  ingress {
    description = "Backend port"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"] # simplu pt demo; in prod: ALB/WAF/allowlist
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# IAM role for EC2 to read/write in your telemetry bucket
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backend_role" {
  name               = "mcp-backend-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

data "aws_iam_policy_document" "backend_s3_policy" {
  statement {
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:ListBucket"
    ]
    resources = [
      "arn:aws:s3:::${var.telemetry_bucket}",
      "arn:aws:s3:::${var.telemetry_bucket}/*"
    ]
  }

  statement {
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:DeleteObject"
    ]
    resources = [
      "arn:aws:s3:::${var.telemetry_bucket}/incoming/*",
      "arn:aws:s3:::${var.telemetry_bucket}/scored/*",
      "arn:aws:s3:::${var.telemetry_bucket}/deploy/*"
    ]
  }
}

resource "aws_iam_policy" "backend_policy" {
  name   = "mcp-backend-s3-policy"
  policy = data.aws_iam_policy_document.backend_s3_policy.json
}

resource "aws_iam_role_policy_attachment" "attach_backend_policy" {
  role       = aws_iam_role.backend_role.name
  policy_arn = aws_iam_policy.backend_policy.arn
}

resource "aws_iam_instance_profile" "backend_profile" {
  name = "mcp-backend-ec2-profile"
  role = aws_iam_role.backend_role.name
}

# User data installs Java17 + Python, pulls your bundle from S3, starts systemd service
locals {
  user_data = <<-EOF
    #!/bin/bash
    set -euo pipefail

    dnf -y update
    dnf -y install java-17-amazon-corretto-headless python3 python3-pip awscli tar

    mkdir -p /opt/mcp-backend
    cd /opt/mcp-backend

    # Pull backend bundle from S3 (you will upload it once)
    aws s3 cp s3://${var.telemetry_bucket}/${var.app_bundle_s3_key} /opt/mcp-backend/backend_bundle.tar.gz
    tar -xzf backend_bundle.tar.gz

    # Install Python deps (if you ship requirements.txt inside bundle)
    if [ -f requirements.txt ]; then
      pip3 install -r requirements.txt
    fi

    # Create application.properties for EC2 (use your existing bucket + paths)
    cat > /opt/mcp-backend/application.properties <<EOP
    bucket.name=${var.telemetry_bucket}

    scorer.python=python3
    scorer.script=/opt/mcp-backend/scorer.py
    scorer.model=/opt/mcp-backend/iforest.joblib

    quarkus.http.port=8080
    quarkus.http.host=0.0.0.0

    # CORS: allow your UI CloudFront domain
    quarkus.http.cors=true
    quarkus.http.cors.origins=${var.ui_origin}
    quarkus.http.cors.methods=GET,POST,OPTIONS
    quarkus.http.cors.headers=Content-Type

    # (optional) bigger payloads for base64 upload
    quarkus.http.limits.max-body-size=50M
    EOP

    # systemd unit
    cat > /etc/systemd/system/mcp-backend.service <<'SERVICE'
    [Unit]
    Description=MCP Backend (Quarkus)
    After=network.target

    [Service]
    WorkingDirectory=/opt/mcp-backend
    ExecStart=/usr/bin/java -Dquarkus.config.locations=/opt/mcp-backend/application.properties -jar /opt/mcp-backend/quarkus-run.jar
    Restart=always
    RestartSec=3

    [Install]
    WantedBy=multi-user.target
    SERVICE

    systemctl daemon-reload
    systemctl enable mcp-backend
    systemctl restart mcp-backend
  EOF
}

resource "aws_instance" "backend" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  iam_instance_profile   = aws_iam_instance_profile.backend_profile.name
  vpc_security_group_ids = [aws_security_group.backend_sg.id]
  key_name               = aws_key_pair.thesis.key_name

  user_data = local.user_data

  tags = {
    Name = "mcp-backend"
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }
}

resource "aws_key_pair" "thesis" {
  key_name   = "thesis-ec2-key"
  public_key = file("${path.module}/thesis_ec2_key.pub")
}

output "backend_public_dns" {
  value = aws_instance.backend.public_dns
}

output "backend_public_ip" {
  value = aws_instance.backend.public_ip
}