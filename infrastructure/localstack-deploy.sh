#!/bin/bash

set -e # Exit immediately if a command exits with a non-zero status.

aws --endpoint-url=http://localhost:4566 --region us-east-1 cloudformation deploy \
    --template-file "./cdk.out/localstack.template.json" \
    --stack-name patient-management

aws --endpoint-url=http://localhost:4566 --region us-east-1 elbv2 describe-load-balancers \
    --query "LoadBalancers[0].DNSName" --output text