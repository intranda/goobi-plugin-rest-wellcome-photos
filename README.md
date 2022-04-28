# Goobi Plugin Rest Wellcome Photos

This plugin downloads a zip file from an S3 service unpacks it and creates a process in goobi upon recieving a post request to /wellcome/createeditorials.
This post request should in its body contain a json object with the following key:value pairs:
- bucket: the bucket containing the zip file
- key: the name of the zip file
- templateid: the id of the process template to use for a new process
- updatetemplateid: the id of the process template to use to update an existing process