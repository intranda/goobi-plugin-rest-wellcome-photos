Goobi workflow Plugin: goobi-plugin-rest-wellcome-photos
===========================================================================

<img src="https://goobi.io/wp-content/uploads/logo_goobi_plugin.png" align="right" style="margin:0 0 20px 20px;" alt="Plugin for Goobi workflow" width="175" height="109">

This is a set of REST endpoints to import audio, video or image files from S3 upload bucket, to import Editorial Photography files and to create Goobi processes. The endpoints receive the requests, verify them and create messages for further processing inside of the internal message queue.

This plugin downloads a zip file from an S3 service unpacks it and creates a process in goobi upon recieving a post request to /wellcome/createeditorials. This post request should in its body contain a json object with the following key:value pairs:

bucket: the bucket containing the zip file
key: the name of the zip file
templateid: the id of the process template to use for a new process
updatetemplateid: the id of the process template to use to update an existing process

This is a plugin for Goobi workflow, the open source workflow tracking software for digitisation projects. More information about Goobi workflow is available under https://goobi.io. If you want to get in touch with the user community simply go to https://community.goobi.io.


Plugin details
---------------------------------------------------------------------------

More information about the functionality of this plugin and the complete documentation can be found in the central documentation area at https://docs.goobi.io

Detail                      | Description
--------------------------- | -------------------------------
**Plugin identifier**       | intranda_rest_wellcome_photos
**Plugin type**             | Rest Plugin
**Licence**                 | GPL 2.0 or newer
**Documentation (German)**  | - no documentation available - 
**Documentation (English)** | - no documentation available -


Goobi details
---------------------------------------------------------------------------
Goobi workflow is an open source web application to manage small and large digitisation projects mostly in cultural heritage institutions all around the world. More information about Goobi can be found here:

Detail              | Description
------------------- | --------------------------
**Goobi web site**  | https://www.goobi.io
**Twitter**         | https://twitter.com/goobi
**Goobi community** | https://community.goobi.io


Development
---------------------------------------------------------------------------
This plugin was developed by intranda. If you have any issues, feedback, question or if you are looking for more information about Goobi workflow, Goobi viewer and all our other developments that are used in digitisation projects please get in touch with us.  

Contact           | Details
----------------- | ----------------------------------------------------
**Company name**  | intranda GmbH
**Address**       | Bertha-von-Suttner-Str. 9, 37085 Göttingen, Germany
**Web site**      | https://www.intranda.com
**Twitter**       | https://twitter.com/intranda