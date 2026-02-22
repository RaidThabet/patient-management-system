#!/bin/bash
#
#set -e # Exit immediately if a command exits with a non-zero status.
#
#aws --endpoint-url=http://localhost:4566 --region us-east-1 cloudformation deploy \
#    --template-file "./cdk.out/localstack.template.json" \
#    --stack-name patient-management
#
#aws --endpoint-url=http://localhost:4566 --region us-east-1 elbv2 describe-load-balancers \
#    --query "LoadBalancers[0].DNSName" --output text



set -e

BUCKET_NAME="cdk-assets-bucket"
ENDPOINT="http://localhost:4566"
REGION="us-east-1"
STACK_NAME="patient-management"

echo "Creating S3 bucket..."
aws --endpoint-url=$ENDPOINT --region $REGION s3 mb s3://$BUCKET_NAME 2>/dev/null || true

echo "Uploading template..."
aws --endpoint-url=$ENDPOINT --region $REGION s3 cp \
    "./cdk.out/localstack.template.json" \
    "s3://$BUCKET_NAME/localstack.template.json"

S3_URL="http://$BUCKET_NAME.s3.localhost.localstack.cloud:4566/localstack.template.json"

echo "Deploying stack..."
aws --endpoint-url=$ENDPOINT --region $REGION cloudformation create-stack \
    --stack-name $STACK_NAME \
    --template-url $S3_URL \
    --capabilities CAPABILITY_IAM || \
aws --endpoint-url=$ENDPOINT --region $REGION cloudformation update-stack \
    --stack-name $STACK_NAME \
    --template-url $S3_URL \
    --capabilities CAPABILITY_IAM

echo "Waiting for stack to complete..."
aws --endpoint-url=$ENDPOINT --region $REGION cloudformation wait stack-create-complete \
    --stack-name $STACK_NAME 2>/dev/null || \
aws --endpoint-url=$ENDPOINT --region $REGION cloudformation wait stack-update-complete \
    --stack-name $STACK_NAME

echo "Fetching Load Balancer DNS..."
aws --endpoint-url=$ENDPOINT --region $REGION elbv2 describe-load-balancers \
    --query "LoadBalancers[0].DNSName" --output text